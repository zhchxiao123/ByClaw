jest.mock('@umijs/max', () => ({
  useDispatch: () => jest.fn(),
  useSelector: (selector: any) => selector({ employees: { employees: [] } }),
  getDvaApp: () => ({ _store: { getState: () => ({ user: { userInfo: { userId: 1 } } }) } }),
}));
jest.mock('@/hooks/useGlobal', () => ({
  __esModule: true,
  default: () => ({ EventEmitter: { emit: jest.fn() }, agentInfo: {}, sessionId: '', setSessionId: jest.fn() }),
}));

import { renderHook } from '@testing-library/react';
import { flow } from 'lodash';
import useHandler from '@/hooks/useChat/useHandler';
import { parseChatStreamMessage } from '@/hooks/useSseSender/chatStream';
import { isV2Message, mergeOrderedItems } from '@/components/MessageList/components/MsgRendererV2/ordered';
import { POST } from '@/service/common/request';
import webSocketManager from '@/utils/websocket';
import { resumeDirectTurn, SegmentSequencer, toChatFrame, withDirectStream, type DirectTurnHandOff } from '../directTurnStream';

jest.mock('@/service/common/request', () => ({ POST: jest.fn() }));

const handOff: DirectTurnHandOff = {
  requestId: 'r1',
  sessionId: '900',
  traceId: 't1',
  messageId: '902',
  queryMessageId: '901',
  watch: 'w',
  signature: 's',
};

const delta = (eventType: string, contentType: string, orderId: string, content: string, metadata: any = {}) => ({
  trace_id: 't1',
  session_id: '900',
  event_type: eventType,
  source_agent_type: 'BYCLAW_DSH',
  data: { contentType, orderId, choices: [{ index: 0, delta: { role: 'assistant', content } }] },
  metadata: { session_scope: 'parent', ...metadata },
});

const turn = [
  delta('answerDelta', '1002', 'm:runtime', '{"status":"running"}', {
    event_kind: 'session.runtime',
    session_status: 'running',
    active_agent_count: 1,
    runtime_revision: 1,
    runtime_changed_at: 1,
  }),
  delta('reasoningLogDelta', '1001', 'm:r:1', 'think '),
  delta('reasoningLogDelta', '1001', 'm:r:1', 'more'),
  delta('answerDelta', '1002', '902', 'I will look. '),
  delta('reasoningLogDelta', '3015', 'call_1', '{"title":"bash","input":"ls"}'),
  delta('reasoningLogDelta', '1001', 'm:r:2', 'it ran'),
  delta('reasoningLogDelta', '3015', 'call_1', '{"output":"bin"}'),
  delta('answerDelta', '1002', '902', 'Done.'),
  delta('answerDelta', '1002', '902', ' Really.'),
  { trace_id: 't1', session_id: '900', event_type: 'appStreamResponse', data: {}, metadata: {} },
];

describe('directTurnStream', () => {
  it('numbers segments like the platform: continue the last, keep in-place items, open the rest', () => {
    const sequencer = new SegmentSequencer();
    const frames = turn.map((event) => toChatFrame(event as any, handOff, 'c1', sequencer));
    expect(frames[0]).toMatchObject({ type: 'SESSION_RUNTIME_STATUS', sessionId: '900', data: { status: 'running', active: true } });
    expect(frames.slice(1, 9).map((frame) => frame?.data.seq)).toEqual([1, 1, 2, 3, 4, 3, 5, 5]);
    expect(frames[1]).toMatchObject({
      type: 'CHAT_STREAM',
      event: 'reasoningLogDelta',
      clientRequestId: 'c1',
      data: { messageRenderVersion: 'v2', eventType: 'reasoningLogDelta', sessionId: '900', traceId: 't1' },
    });
    expect(frames[9]).toMatchObject({ event: 'appStreamResponse', data: { messageId: '902', queryMessageId: '901' } });
  });

  it('builds an ordered message through the page handlers', () => {
    const { result } = renderHook(() => useHandler({ addSession: jest.fn(), setSessionId: jest.fn() } as any));
    const h = result.current as any;
    const run = flow(
      [h.sessionInfoHandler, h.messageIdHandler, h.queryMessageIdHandler, h.textHandler, h.messageHandler].filter(Boolean)
    );
    const answer: any = { messageList: [], thinkList: [], msgId: 'a' };
    const sequencer = new SegmentSequencer();
    turn.forEach((event) => {
      const frame = toChatFrame(event as any, handOff, 'c1', sequencer);
      const parsed = frame?.type === 'CHAT_STREAM' ? parseChatStreamMessage(frame) : undefined;
      if (!parsed) return;
      run({ sseRes: parsed.formattedPayload, sseMsg: { ...parsed.sseMsg, clientRequestId: 'c1' }, newQueryMsg: {}, newAnswerMsg: answer, messageList: [] });
    });
    expect(isV2Message(answer)).toBe(true);
    expect(mergeOrderedItems(answer.thinkList, answer.messageList).map(({ item, channel }) => `${channel[0]}${item.seq}`)).toEqual([
      't1',
      'a2',
      't3',
      't4',
      'a5',
    ]);
  });

  it('ignores child-session output and marks only commands with a client request', () => {
    expect(toChatFrame(delta('answerDelta', '1002', 'x', 'child', { session_scope: 'child' }) as any, handOff, 'c1', new SegmentSequencer())).toBeUndefined();
    expect(withDirectStream({ type: 'LLM_MESSAGE', clientRequestId: 'c9', extParams: { requestId: 'q' } })).toEqual({
      type: 'LLM_MESSAGE',
      clientRequestId: 'c9',
      extParams: { requestId: 'q', directStream: true },
    });
    expect(withDirectStream({ type: 'LLM_MESSAGE' })).toEqual({ type: 'LLM_MESSAGE' });
  });

  it('reads a running turn again after a reload, only when the platform signs a fresh ticket', async () => {
    const post = POST as jest.Mock;
    const deliver = jest.spyOn(webSocketManager, 'deliverLocal').mockImplementation(() => undefined);
    const body = [
      JSON.stringify({ type: 'event', seq: 1, data: delta('answerDelta', '1002', 'm1', 'hi') }),
      JSON.stringify({ type: 'end', state: 'completed' }),
    ].join('\n');
    const fetchMock = jest.fn(async () => {
      let read = false;
      return {
        ok: true,
        body: {
          getReader: () => ({
            read: async () => (read ? { done: true } : ((read = true), { done: false, value: new TextEncoder().encode(body) })),
          }),
        },
      };
    });
    (global as any).fetch = fetchMock;

    post.mockResolvedValueOnce(handOff).mockResolvedValueOnce(handOff).mockResolvedValue({});
    // The tab that sent the turn restores the same session while it reads it: one read only.
    await Promise.all([resumeDirectTurn('900', 'restored-1'), resumeDirectTurn('900', 'restored-1')]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenNthCalledWith(1, '/byaiService/direct/stream/rewatch', { sessionId: 900 }, expect.anything());
    expect(deliver).toHaveBeenCalledWith(
      expect.objectContaining({ clientRequestId: 'restored-1', event: 'answerDelta', data: expect.objectContaining({ seq: 1 }) })
    );
    expect(deliver).not.toHaveBeenCalledWith(expect.objectContaining({ event: 'error' }));

    post.mockReset();
    fetchMock.mockClear();
    post.mockResolvedValueOnce(null);
    await resumeDirectTurn('901', 'restored-2');
    expect(fetchMock).not.toHaveBeenCalled();
    deliver.mockRestore();
  });
});
