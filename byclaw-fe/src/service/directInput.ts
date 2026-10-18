/**
 * Input while a Direct session answers, as in DSH: words steered into the running answer, or a
 * message still waiting behind it taken back.
 */
import { POST } from '@/service/common/request';

const quiet = { responseCfg: { hideErrorTips: true } };

/** True when the words joined the running answer; false when none runs (send them as a message then). */
export const steerDirectInput = async (sessionId: string, text: string) => {
  const result = await POST<{ steered?: boolean } | null>(
    '/byaiService/direct/input/steer',
    { sessionId: Number(sessionId), text },
    quiet
  );
  return result?.steered === true;
};

/** `withdrawn` when the waiting message will never run (its question and answer are removed). */
export const withdrawDirectInput = async (sessionId: string, answerMessageId: string) => {
  const result = await POST<{ result?: string } | null>(
    '/byaiService/direct/input/withdraw',
    { sessionId: Number(sessionId), answerMessageId: Number(answerMessageId) },
    quiet
  );
  return result?.result;
};
