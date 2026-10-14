jest.mock('dompurify', () => ({
  sanitize: jest.fn((value: string) => `safe:${value}`),
}));

jest.mock('@umijs/max', () => ({
  getLocale: jest.fn(() => 'en-US'),
  useDispatch: jest.fn(),
}));

jest.mock('@/utils/websocket', () => ({
  __esModule: true,
  default: {
    onMessage: jest.fn(),
    offMessage: jest.fn(),
    sendMessageWhenReady: jest.fn(() => Promise.resolve()),
  },
}));

import { renderHook, act } from '@testing-library/react';
import { useDispatch } from '@umijs/max';
import DOMPurify from 'dompurify';

import webSocketManager from '@/utils/websocket';

import useSend from '../useSseSender/useSend';
import type { ChatCommand, ChatTransport } from '@/service/chatTransport';

const mockUseDispatch = useDispatch as jest.Mock;
const mockWebSocketManager = webSocketManager as unknown as {
  onMessage: jest.Mock;
  offMessage: jest.Mock;
  sendMessageWhenReady: jest.Mock;
};

describe('hooks/useSseSender/useSend', () => {
  let dispatch: jest.Mock;

  beforeEach(() => {
    jest.clearAllMocks();
    dispatch = jest.fn();
    mockUseDispatch.mockReturnValue(dispatch);
  });

  it('uses websocket for sending and sanitizes outgoing text', async () => {
    const { result } = renderHook(() =>
      useSend({
        sessionId: 'session-1',
        language: 'en',
        chatUrl: '/chat/url',
      })
    );

    await act(async () => {
      const { promise } = result.current.send('<b>hello</b>', { foo: 'bar' });
      await expect(promise).resolves.toEqual({});
    });

    expect(mockWebSocketManager.sendMessageWhenReady).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'LLM_MESSAGE',
        chatContent: 'safe:<b>hello</b>',
        relModelId: -1,
        accessTerminal: 'Web',
        sessionId: 'session-1',
        chatId: 'session-1',
        foo: 'bar',
      })
    );
    expect(DOMPurify.sanitize).toHaveBeenCalledWith('<b>hello</b>');
    expect(mockWebSocketManager.onMessage).not.toHaveBeenCalledWith('CHAT_STREAM', expect.any(Function));
  });

  it('preserves request identity and business payload with a replacement transport', async () => {
    const commands: ChatCommand[] = [];
    const transport: ChatTransport = {
      sendCommand: async (command) => {
        commands.push(command);
      },
      subscribe: () => () => {},
    };
    const { result } = renderHook(() => useSend({ sessionId: 'session-direct', transport }));

    await act(async () => {
      await result.current.send('review the project', {
        clientRequestId: 'request-42',
        agentId: 'employee-7',
        projectId: 'project-9',
        extParams: { resourceIds: ['resource-3'] },
      }).promise;
    });

    expect(commands).toEqual([
      {
        type: 'LLM_MESSAGE',
        clientRequestId: 'request-42',
        language: 'en-US',
        chatContent: 'safe:review the project',
        relModelId: -1,
        accessTerminal: 'Web',
        sessionId: 'session-direct',
        chatId: 'session-direct',
        agentId: 'employee-7',
        projectId: 'project-9',
        extParams: { resourceIds: ['resource-3'], requestId: 'request-42' },
      },
    ]);
    expect(mockWebSocketManager.sendMessageWhenReady).not.toHaveBeenCalled();
  });

  it('sends openclaw payload through websocket manager', async () => {
    const { result } = renderHook(() =>
      useSend({
        sessionId: 'session-2',
        agentType: '013',
        chatUrl: '/chat/url',
      })
    );

    await act(async () => {
      const { promise } = result.current.send('hello', {
        agentType: '013',
        extParams: {
          clientId: 'client-1',
        },
      });
      await expect(promise).resolves.toEqual({});
    });

    expect(mockWebSocketManager.sendMessageWhenReady).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'LLM_MESSAGE',
        chatContent: 'safe:hello',
        sessionId: 'session-2',
        chatId: 'session-2',
        agentType: '013',
        extParams: {
          clientId: 'client-1',
        },
      })
    );
  });
});
