import React, { Suspense } from 'react';
import { set } from 'lodash';

import NotSupport from '@/components/NotSupport';
import lazyHandler from '@/components/MessageList/lazyHandler';
import type { IMessage, IMessageListItem } from '@/typescript/message';
import { isContextItem, isRowItem, toTimelineRow } from './rowModel';
import TimelineRow from './TimelineRow';
import styles from './index.module.less';

type Props = {
  items: IMessageListItem[];
  message: IMessage;

  /** The message is still being answered and this group holds its newest item. */
  live: boolean;
  updateMessage: (message: IMessage) => IMessage | void;
};

/**
 * A run of the thinking channel drawn as steps: thinking and tool calls one per line, runtime
 * context omitted, and every other item (questions, plans, team cards) through its own renderer.
 */
export default function Timeline({ items, message, live, updateMessage }: Props) {
  const visible = items.filter((item) => !isContextItem(item));
  if (visible.length === 0) return null;
  return (
    <div className={styles.timeline}>
      {visible.map((item, index) => {
        const key = `${message.msgId}_${item.seq}`;
        if (isRowItem(item)) {
          const row = toTimelineRow(item, live && index === visible.length - 1);
          return row ? <TimelineRow key={key} row={row} /> : null;
        }
        const Comp = lazyHandler.lazyComp(`${item.contentType}`) as React.ComponentType<any> | null;
        if (!Comp) return <NotSupport key={key} />;
        return (
          <Suspense key={key}>
            <Comp
              message={message}
              thinkListItem={item}
              messageListItem={item}
              messageListItemContent={item.content}
              updateMessageListItemContent={(content: IMessageListItem['content']) => {
                const position = message.thinkList?.findIndex((source) => source.seq === item.seq) ?? -1;
                if (position < 0) return message;
                const next = { ...message };
                set(next, `thinkList.${position}.content`, content);
                updateMessage(next);
                return next;
              }}
            />
          </Suspense>
        );
      })}
    </div>
  );
}
