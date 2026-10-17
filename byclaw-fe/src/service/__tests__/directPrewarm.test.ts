import { POST } from '@/service/common/request';
import { PREWARM_INTERVAL_MS, prewarmDirectRuntime, resetPrewarm } from '../directPrewarm';

jest.mock('@/service/common/request', () => ({
  POST: jest.fn(() => Promise.resolve()),
}));

describe('prewarmDirectRuntime', () => {
  beforeEach(() => {
    resetPrewarm();
    (POST as jest.Mock).mockClear();
  });

  it('names the conversation being written, without error tips', () => {
    prewarmDirectRuntime({ sessionId: '9000', agentId: '10000153', projectId: '' }, 1000);
    expect(POST).toHaveBeenCalledWith(
      '/byaiService/direct/runtime/prewarm',
      { sessionId: 9000, agentId: 10000153, projectId: undefined },
      { responseCfg: { hideErrorTips: true } }
    );
  });

  it('asks once a minute per conversation, and again for another one', () => {
    prewarmDirectRuntime({ agentId: 1 }, 1000);
    prewarmDirectRuntime({ agentId: 1 }, 1000 + PREWARM_INTERVAL_MS - 1);
    expect(POST).toHaveBeenCalledTimes(1);
    prewarmDirectRuntime({ agentId: 2 }, 2000);
    expect(POST).toHaveBeenCalledTimes(2);
    prewarmDirectRuntime({ agentId: 2 }, 2000 + PREWARM_INTERVAL_MS);
    expect(POST).toHaveBeenCalledTimes(3);
  });

  it('ignores a failed request', async () => {
    (POST as jest.Mock).mockReturnValueOnce(Promise.reject(new Error('404')));
    expect(() => prewarmDirectRuntime({ agentId: 1 }, 1000)).not.toThrow();
    await Promise.resolve();
  });
});
