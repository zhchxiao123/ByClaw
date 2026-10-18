/**
 * Direct turns the page streams itself.
 *
 * The platform still prepares the turn, wakes the runtime and sends it the command (with its
 * server-side credentials); once the runtime admitted it, this tab gets a read-only watch ticket as
 * a `directTurn` chat event. The page reads the turn's output through `/dsh/watch` (the proxy asks
 * the platform to authorize the ticket), turns the runtime's events into the chat frames the
 * platform would have pushed (segment seq included), and asks the platform to persist the turn when
 * its stream ends. History keeps reading the platform; a reload mid-turn reads the turn again.
 */
import type { ChatCommand } from '@/service/chatTransport';
import { POST } from '@/service/common/request';
import { getssoToken, getToken } from '@/utils/auth';
import webSocketManager from '@/utils/websocket';

export const DIRECT_STREAM_PARAM = 'directStream';
export const DIRECT_TURN_EVENT = 'directTurn';
const ROUTE = '/dsh/watch';
const PENDING_TTL_MS = 10 * 60 * 1000;

export type DirectTurnHandOff = {
  requestId: string;
  sessionId: string;
  traceId: string;
  messageId: string;
  queryMessageId?: string;
  watch: string;
  signature: string;
};

type RuntimeEvent = {
  trace_id?: string;
  session_id?: string;
  event_type?: string;
  source_agent_type?: string;
  data?: Record<string, any>;
  metadata?: Record<string, any>;
};

type Frame = Record<string, any> & { type: string };

/** Content types the platform updates in place by order id instead of appending a segment. */
const IN_PLACE_CONTENT_TYPES = new Set(['3015', '3009', '2008']);

/**
 * The platform's segment numbering (MessageContext): one cross-channel sequence; a delta continues
 * the last segment when its channel, content type and order id match it, an in-place item keeps the
 * number of its first frame, anything else opens the next segment.
 */
export class SegmentSequencer {
  private nextSeq = 1;

  private last?: { channel: string; contentType: string; orderId: string; seq: number };

  private readonly inPlace = new Map<string, number>();

  seqFor(channel: string, contentType = '', orderId = ''): number {
    if (IN_PLACE_CONTENT_TYPES.has(contentType) && orderId) {
      const key = `${channel}\u0000${contentType}\u0000${orderId}`;
      const known = this.inPlace.get(key);
      if (known !== undefined) return known;
      const seq = this.open(channel, contentType, orderId);
      this.inPlace.set(key, seq);
      return seq;
    }
    const last = this.last;
    if (last && last.channel === channel && last.contentType === contentType && last.orderId === orderId) return last.seq;
    return this.open(channel, contentType, orderId);
  }

  private open(channel: string, contentType: string, orderId: string) {
    const seq = this.nextSeq;
    this.nextSeq += 1;
    this.last = { channel, contentType, orderId, seq };
    return seq;
  }
}

const metadataOf = (event: RuntimeEvent) => (event.metadata && typeof event.metadata === 'object' ? event.metadata : {});

/** The runtime state frame the platform broadcasts for a `session.runtime` event of the parent session. */
const runtimeStatusFrame = (event: RuntimeEvent, handOff: DirectTurnHandOff): Frame => {
  const metadata = metadataOf(event);
  const status = metadata.session_status;
  const activeAgentCount = metadata.active_agent_count;
  const waitingInteractionCount = metadata.waiting_interaction_count;
  return {
    type: 'SESSION_RUNTIME_STATUS',
    sessionId: handOff.sessionId,
    traceId: event.trace_id,
    data: {
      sessionId: handOff.sessionId,
      traceId: event.trace_id,
      source: metadata.event_source,
      status,
      rootActive: metadata.root_active,
      acceptingInput: metadata.accepting_input,
      activeAgentCount,
      activeChildCount: metadata.active_child_count,
      waitingInteractionCount,
      revision: metadata.runtime_revision,
      changedAt: metadata.runtime_changed_at,
      active: status === 'running' || status === 'waiting_user' || activeAgentCount > 0 || waitingInteractionCount > 0,
    },
  };
};

/** Turns one runtime event into the frame the platform would push to this tab, or nothing. */
export const toChatFrame = (
  event: RuntimeEvent,
  handOff: DirectTurnHandOff,
  clientRequestId: string,
  sequencer: SegmentSequencer
): Frame | undefined => {
  const eventType = event.event_type;
  if (!eventType) return undefined;
  const metadata = metadataOf(event);
  if (metadata.event_kind === 'session.runtime') {
    return metadata.session_scope === 'parent' ? runtimeStatusFrame(event, handOff) : undefined;
  }
  // Child-session output is projected by the platform; this tab renders its own session only.
  if (metadata.session_scope && metadata.session_scope !== 'parent') return undefined;
  const envelope = { type: 'CHAT_STREAM', event: eventType, clientRequestId, sessionId: handOff.sessionId };
  if (eventType === 'appStreamResponse') {
    return {
      ...envelope,
      data: {
        event: eventType,
        messageId: handOff.messageId,
        queryMessageId: handOff.queryMessageId,
        sessionId: handOff.sessionId,
        traceId: handOff.traceId,
      },
    };
  }
  if (eventType === 'error') {
    return {
      ...envelope,
      data: { event: eventType, message: metadata.error, traceId: handOff.traceId, sessionId: handOff.sessionId },
    };
  }
  const data: Record<string, any> = {
    ...(event.data || {}),
    event: eventType,
    traceId: event.trace_id,
    metadata: event.metadata,
    sourceAgentType: event.source_agent_type,
    sessionId: handOff.sessionId,
  };
  const channel = eventType === 'reasoningLogDelta' ? 'reasoningLogDelta' : eventType === 'answerDelta' ? 'answerDelta' : '';
  if (channel) {
    data.seq = sequencer.seqFor(channel, `${data.contentType ?? ''}`, `${data.orderId ?? ''}`);
    data.eventType = channel;
    data.messageRenderVersion = 'v2';
  }
  return { ...envelope, data };
};

const deliverError = (handOff: DirectTurnHandOff, clientRequestId: string, message: string) =>
  webSocketManager.deliverLocal({
    type: 'CHAT_STREAM',
    event: 'error',
    clientRequestId,
    sessionId: handOff.sessionId,
    data: { event: 'error', message, traceId: handOff.traceId, sessionId: handOff.sessionId },
  });

const settle = (sessionId: string) =>
  POST<any>('/byaiService/direct/stream/settle', { sessionId: Number(sessionId) }).catch((error: unknown) => {
    // The platform's own sweep persists the turn later; the page already shows it.
    console.warn('直连对话补录请求失败，将由平台稍后补录', error);
  });

/**
 * A page that is going away (reload, navigation) aborts its stream without having read the turn to
 * its end: it leaves the turn held, so the reloaded page reads it again live via `resumeDirectTurn`.
 */
let unloading = false;
if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', () => {
    unloading = true;
  });
  window.addEventListener('pageshow', () => {
    unloading = false;
  });
}

/** Turns this tab is reading (session and request), so a restore never reads one a second time. */
const reading = new Set<string>();
const turnKey = (handOff: DirectTurnHandOff) => `${handOff.sessionId}\u0000${handOff.requestId}`;

/** Reads the handed-off turn's output from the runtime and renders it in this tab. */
export const streamDirectTurn = async (handOff: DirectTurnHandOff, clientRequestId: string) => {
  const sequencer = new SegmentSequencer();
  let ended = false;
  const key = turnKey(handOff);
  if (reading.has(key)) return;
  reading.add(key);
  try {
    const response = await fetch(ROUTE, {
      headers: {
        'Beyond-Token': getToken(),
        'SSO-TOKEN': getssoToken(),
        'x-byclaw-direct-watch': handOff.watch,
        'x-byclaw-direct-signature': handOff.signature,
      },
    });
    if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    const handleLine = (line: string) => {
      if (!line.trim()) return;
      const item = JSON.parse(line);
      if (item.type === 'end') ended = true;
      if (item.type !== 'event') return;
      const frame = toChatFrame(item.data || {}, handOff, clientRequestId, sequencer);
      if (!frame) return;
      if (frame.event === 'appStreamResponse' || frame.event === 'error') ended = true;
      webSocketManager.deliverLocal(frame);
    };
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let index = buffer.indexOf('\n');
      while (index >= 0) {
        handleLine(buffer.slice(0, index));
        buffer = buffer.slice(index + 1);
        index = buffer.indexOf('\n');
      }
    }
    handleLine(buffer);
    if (!ended) deliverError(handOff, clientRequestId, '与运行环境的连接中断，已生成的内容会在刷新后显示');
  } catch (error) {
    console.error('直连运行环境失败', error);
    if (!ended) deliverError(handOff, clientRequestId, '无法直连运行环境，请稍后重试');
  } finally {
    reading.delete(key);
    if (!unloading) void settle(handOff.sessionId);
  }
};

/**
 * A reloaded page restoring a running turn: when an earlier load of the page streamed that turn
 * itself, the platform holds nothing of it until that stream settles. The platform signs a fresh
 * ticket for the same turn, which this page reads from its start into the restored answer.
 */
export const resumeDirectTurn = async (sessionId: string, clientRequestId: string) => {
  let handOff: DirectTurnHandOff | null | undefined;
  try {
    handOff = await POST<DirectTurnHandOff | null>(
      '/byaiService/direct/stream/rewatch',
      { sessionId: Number(sessionId) },
      { responseCfg: { hideErrorTips: true } }
    );
  } catch (error) {
    console.warn('直连对话重新读取失败', error);
    return;
  }
  if (handOff?.watch) await streamDirectTurn(handOff, clientRequestId);
};

/** Requests this tab sent; only the sending tab streams a hand-off; a reloaded page reads it again via `resumeDirectTurn`. */
const pending = new Map<string, number>();
let installed = false;

const install = () => {
  if (installed) return;
  installed = true;
  webSocketManager.onMessage('CHAT_STREAM', (message) => {
    const data = typeof message.data === 'string' ? JSON.parse(message.data || '{}') : message.data || {};
    if ((message.event || data.event) !== DIRECT_TURN_EVENT) return;
    const clientRequestId = `${message.clientRequestId || ''}`;
    if (!pending.delete(clientRequestId)) return;
    void streamDirectTurn(data as DirectTurnHandOff, clientRequestId);
  });
};

/** Marks a chat command as willing to stream its Direct turn from the runtime itself. */
export const withDirectStream = (command: ChatCommand): ChatCommand => {
  if (!command.clientRequestId) return command;
  install();
  const now = Date.now();
  pending.forEach((at, id) => {
    if (now - at > PENDING_TTL_MS) pending.delete(id);
  });
  pending.set(command.clientRequestId, now);
  const extParams = command.extParams && typeof command.extParams === 'object' ? command.extParams : {};
  return { ...command, extParams: { ...extParams, [DIRECT_STREAM_PARAM]: true } };
};
