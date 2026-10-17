/**
 * Asks the platform to start the Direct runtime of the conversation being written, so the turn
 * about to be sent finds it ready instead of waiting for a cold start. The platform decides
 * whether that turn would run on Direct; this only says which conversation it is, at most once a
 * minute per conversation.
 */
import { POST } from '@/service/common/request';

export const PREWARM_INTERVAL_MS = 60 * 1000;

export type PrewarmConversation = {
  sessionId?: string | number;
  agentId?: string | number;
  projectId?: string | number;
};

let last: { key: string; at: number } | undefined;

const idOf = (value: string | number | undefined) => {
  const id = Number(value);
  return Number.isFinite(id) && id > 0 ? id : undefined;
};

export const prewarmDirectRuntime = (conversation: PrewarmConversation, now = Date.now()) => {
  const body = {
    sessionId: idOf(conversation.sessionId),
    agentId: idOf(conversation.agentId),
    projectId: idOf(conversation.projectId),
  };
  const key = JSON.stringify(body);
  if (last && last.key === key && now - last.at < PREWARM_INTERVAL_MS) return;
  last = { key, at: now };
  POST('/byaiService/direct/runtime/prewarm', body, { responseCfg: { hideErrorTips: true } }).catch(() => {
    // Only an early start: the turn itself reports a runtime that cannot start.
  });
};

/** Test hook. */
export const resetPrewarm = () => {
  last = undefined;
};
