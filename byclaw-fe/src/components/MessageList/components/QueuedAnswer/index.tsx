/** An answer still waiting behind the session's running one: it can be taken back, or taken back to edit. */
import React, { useState } from 'react';
import { Button, message } from 'antd';
import { getIntl } from '@umijs/max';
import useGlobal from '@/hooks/useGlobal';
import { unregisterPendingChatContext, unregisterSessionChatContext } from '@/hooks/useChat/chatRuntime';
import { withdrawDirectInput } from '@/service/directInput';
import { chatSessionRuntimeManager } from '@/utils/chatSessionRuntimeManager';
import type { IMessage } from '@/typescript/message';
import styles from './index.module.less';

type Props = {
  msg: IMessage;
  sessionId?: string;
  deleteMessage: (message: IMessage) => void;
};

const t = (id: string) => getIntl().formatMessage({ id });

export default function QueuedAnswer({ msg, sessionId, deleteMessage }: Props) {
  const { EventEmitter } = useGlobal();
  const [pending, setPending] = useState(false);
  const targetSessionId = `${msg.sessionId || sessionId || ''}`;

  const takeBack = async (edit: boolean) => {
    if (!msg.messageId || !targetSessionId || pending) return;
    setPending(true);
    try {
      const result = await withdrawDirectInput(targetSessionId, msg.messageId);
      if (result !== 'withdrawn') {
        message.info(t('queuedAnswer.started'));
        return;
      }
      // The platform already removed both messages; drop them here and stop tracking the turn.
      const clientRequestId = `${msg.queryMsgId}_${msg.msgId}`;
      unregisterSessionChatContext(targetSessionId, clientRequestId);
      unregisterPendingChatContext(clientRequestId);
      chatSessionRuntimeManager.complete(clientRequestId);
      deleteMessage({ ...msg, messageId: undefined });
      if (msg.queryMsgId) deleteMessage({ msgId: msg.queryMsgId, sessionId: msg.sessionId } as IMessage);
      if (edit && msg.queuedText) EventEmitter.emit('queryInput-set-value', msg.queuedText);
    } catch (error) {
      console.warn('撤回排队消息失败', error);
      message.error(t('queuedAnswer.failed'));
    } finally {
      setPending(false);
    }
  };

  return (
    <div className={styles.queued}>
      <span className={styles.label}>{t('queuedAnswer.waiting')}</span>
      <Button type="link" size="small" disabled={!msg.messageId || pending} onClick={() => takeBack(true)}>
        {t('queuedAnswer.edit')}
      </Button>
      <Button type="link" size="small" disabled={!msg.messageId || pending} onClick={() => takeBack(false)}>
        {t('queuedAnswer.withdraw')}
      </Button>
    </div>
  );
}
