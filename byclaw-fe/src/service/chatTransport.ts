/** Chat commands and projections vary independently of platform notifications. */
import webSocketManager from '@/utils/websocket';
import { withDirectStream } from '@/service/directTurnStream';
import type { TaskPlanSnapshot } from '@/typescript/message';
import type { SessionRuntimeState } from '@/utils/chatSessionRuntimeManager';

export type ChatRuntimeEvent = 'CHAT_STREAM' | 'ERROR' | 'TASK_PLAN_SNAPSHOT' | 'SESSION_RUNTIME_STATUS';

export interface ChatCommand {
  type: 'LLM_MESSAGE';
  sessionId?: string;
  clientRequestId?: string;
  [key: string]: unknown;
}

export interface ChatRuntimeMessage {
  type: string;
  sessionId?: string;
  clientRequestId?: string;
  event?: string;
  data?: unknown;
  [key: string]: unknown;
}

export type ChatRuntimeListener = (message: ChatRuntimeMessage) => void;

export interface ChatRuntimeEventMap {
  CHAT_STREAM: ChatRuntimeMessage;
  ERROR: ChatRuntimeMessage;
  TASK_PLAN_SNAPSHOT: {
    type: string;
    sessionId?: string;
    messageId?: string;
    data?: TaskPlanSnapshot;
  };
  SESSION_RUNTIME_STATUS: {
    type: string;
    sessionId?: string;
    traceId?: string;
    data?: SessionRuntimeState | string;
  };
}

/** Disconnecting a subscription never cancels an accepted Agent turn. */
export interface ChatTransport {
  sendCommand(command: ChatCommand): Promise<void>;
  subscribe<Event extends ChatRuntimeEvent>(
    event: Event,
    listener: (message: ChatRuntimeEventMap[Event]) => void
  ): () => void;
}

/** Legacy keeps its existing connection, readiness, and wire contract. */
export const legacyChatTransport: ChatTransport = {
  sendCommand: (command) => webSocketManager.sendMessageWhenReady(withDirectStream(command)),
  subscribe: (event, listener) => {
    webSocketManager.onMessage(event, listener);
    return () => webSocketManager.offMessage(event, listener);
  },
};
