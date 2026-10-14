import { useEffect } from 'react';
import { useMultiTenancy } from '@/utils/multiTenancy';
import { isEqual } from 'lodash';
import { useDispatch, useSelector } from '@umijs/max';

import { subscribeChatStream } from '@/hooks/useSseSender/chatStream';
import {
  clearChatRuntime,
  handleChatStreamError,
  handleParsedChatStream,
  handleSessionRuntimeState,
  handleTaskPlanSnapshot,
} from '@/hooks/useChat/chatRuntime';
import webSocketManager from '@/utils/websocket';
import { legacyChatTransport, type ChatTransport } from '@/service/chatTransport';
import type { SessionRuntimeState } from '@/utils/chatSessionRuntimeManager';

import type { ISession } from '@/typescript/session';
import type { TaskPlanSnapshot } from '@/typescript/message';

type State = {
  user: {
    userInfo?: {
      userId?: string | number;
    };
  };
};

type INotificationMessage = { session?: ISession; data?: { session?: ISession } };

let lastNotificationInfo: INotificationMessage | null = null;
export default function useGlobalChatRuntime(transport: ChatTransport = legacyChatTransport) {
  const { loaded, enabled } = useMultiTenancy();
  const dispatch = useDispatch();
  const userInfo = useSelector((state: State) => state.user.userInfo);

  const userId = userInfo?.userId;

  useEffect(() => {
    if (!loaded || !userId) {
      webSocketManager.disconnect();
      clearChatRuntime();
      return;
    }

    webSocketManager.init();

    return () => {
      webSocketManager.disconnect();
    };
  }, [userId, loaded, enabled]);

  useEffect(() => {
    if (!loaded || !userId) {
      return;
    }

    const unsubscribeChatStream = subscribeChatStream({
      transport,
      match: () => true,
      onPayload: handleParsedChatStream,
    });

    const handleNotification = (message: INotificationMessage) => {
      const isSame = isEqual(lastNotificationInfo, message); // 幂等校验
      if (isSame) {
        return;
      }
      lastNotificationInfo = message;

      const session = message.session || message.data?.session;
      if (!session || `${userId}` !== `${session.creatorId}` || isSame) {
        return;
      }

      dispatch({
        type: 'session/updateUnreadInfo',
        payload: {
          totalUnread: 1,
        },
      });
      dispatch({
        type: 'session/addNotificationSession',
        payload: session,
      });
    };

    const handleTaskPlan = (message: { data?: TaskPlanSnapshot; sessionId?: string; messageId?: string }) => {
      const taskPlan = message.data;
      if (!taskPlan?.planId || !taskPlan?.sessionId) return;

      // 实时上下文优先更新当前回答消息；store reducer 同时覆盖已缓存会话并处理页面切换场景。
      handleTaskPlanSnapshot(message);
      dispatch({
        type: 'messageStore/applyTaskPlanSnapshot',
        payload: {
          sessionId: `${taskPlan.sessionId || message.sessionId || ''}`,
          messageId: `${taskPlan.messageId || message.messageId || ''}`,
          taskPlan,
        },
      });
    };

    const handleSessionRuntime = (message: {
      data?: SessionRuntimeState | string;
      sessionId?: string;
      traceId?: string;
    }) => {
      try {
        const payload = typeof message.data === 'string' ? JSON.parse(message.data) : message.data;
        if (!payload) return;
        handleSessionRuntimeState({
          ...payload,
          sessionId: `${message.sessionId || payload.sessionId || ''}`,
          traceId: `${payload.traceId || message.traceId || ''}`,
        });
      } catch (error) {
        console.warn('Ignored invalid session runtime message', error);
      }
    };

    const unsubscribeError = transport.subscribe('ERROR', handleChatStreamError);
    webSocketManager.onMessage('NOTIFICATION', handleNotification);
    const unsubscribeTaskPlan = transport.subscribe('TASK_PLAN_SNAPSHOT', handleTaskPlan);
    const unsubscribeRuntime = transport.subscribe('SESSION_RUNTIME_STATUS', handleSessionRuntime);

    return () => {
      unsubscribeChatStream();
      unsubscribeError();
      webSocketManager.offMessage('NOTIFICATION', handleNotification);
      unsubscribeTaskPlan();
      unsubscribeRuntime();
    };
  }, [dispatch, userId, loaded, enabled, transport]);
}
