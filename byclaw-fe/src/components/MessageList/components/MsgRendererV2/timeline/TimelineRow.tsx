import React, { useState } from 'react';
import {
  BookOutlined,
  BulbOutlined,
  CheckSquareOutlined,
  CodeOutlined,
  EditOutlined,
  FileAddOutlined,
  FileTextOutlined,
  GlobalOutlined,
  LoadingOutlined,
  PaperClipOutlined,
  PartitionOutlined,
  RightOutlined,
  SearchOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import classnames from 'classnames';

import type { RowVariant, TimelineRow as Row } from './rowModel';
import styles from './index.module.less';

const ICONS: Record<RowVariant, React.ComponentType> = {
  think: BulbOutlined,
  bash: CodeOutlined,
  read: FileTextOutlined,
  search: SearchOutlined,
  write: FileAddOutlined,
  edit: EditOutlined,
  web: GlobalOutlined,
  todo: CheckSquareOutlined,
  subagent: PartitionOutlined,
  skill: BookOutlined,
  deliver: PaperClipOutlined,
  others: ToolOutlined,
};

/** Expanded body text is capped; the full value stays one click away in the copy. */
const MAX_BODY_CHARS = 20_000;
const clip = (text: string) => (text.length > MAX_BODY_CHARS ? `${text.slice(0, MAX_BODY_CHARS)}\n…` : text);

function Body({ row }: { row: Row }) {
  const intl = useIntl();
  if (row.variant === 'think') return <div className={styles.thinkBody}>{clip(row.text ?? '')}</div>;
  if (row.variant === 'edit' && row.diff) {
    return (
      <pre className={styles.diffBody}>
        {row.diff.removed.map((line, index) => (
          <div key={`r${index}`} className={styles.removed}>{`- ${line}`}</div>
        ))}
        {row.diff.added.map((line, index) => (
          <div key={`a${index}`} className={styles.added}>{`+ ${line}`}</div>
        ))}
      </pre>
    );
  }
  if (row.variant === 'bash') {
    return (
      <pre className={classnames(styles.terminal, { [styles.errorText]: row.state === 'error' })}>
        {row.input && <div className={styles.command}>{`$ ${row.input}`}</div>}
        {row.output && clip(row.output)}
      </pre>
    );
  }
  return (
    <div className={styles.ioCard}>
      {row.input && (
        <div className={styles.ioSection}>
          <span className={styles.ioLabel}>{intl.formatMessage({ id: 'timeline.input' })}</span>
          <pre className={styles.ioText}>{clip(row.input)}</pre>
        </div>
      )}
      {row.output && (
        <div className={styles.ioSection}>
          <span className={styles.ioLabel}>{intl.formatMessage({ id: 'timeline.output' })}</span>
          <pre className={classnames(styles.ioText, { [styles.errorText]: row.state === 'error' })}>
            {clip(row.output)}
          </pre>
        </div>
      )}
    </div>
  );
}

/** One step of the turn on one line (icon · title · summary), expanding to its detail. */
export default function TimelineRow({ row }: { row: Row }) {
  const intl = useIntl();
  const [open, setOpen] = useState(false);
  const Icon = ICONS[row.variant];
  const expandable = Boolean(row.text || row.input || row.output || row.diff);
  const title = row.titleKey ? intl.formatMessage({ id: row.titleKey }) : row.title;
  const leading =
    row.state === 'running' ? (
      <LoadingOutlined />
    ) : row.state === 'error' ? (
      <span className={styles.errorDot} aria-label={intl.formatMessage({ id: 'timeline.failed' })} />
    ) : (
      <Icon />
    );
  return (
    <div className={classnames(styles.row, { [styles.open]: open })}>
      <button
        type="button"
        className={classnames(styles.head, { [styles.expandable]: expandable })}
        aria-expanded={expandable ? open : undefined}
        onClick={() => expandable && setOpen((value) => !value)}
      >
        <span className={styles.leading}>{leading}</span>
        <span className={classnames(styles.title, { [styles.running]: row.state === 'running' })}>{title}</span>
        {row.summary && (
          <>
            <span className={styles.sep} aria-hidden />
            <span className={styles.summary} title={row.summary}>
              {row.summary}
            </span>
          </>
        )}
        {row.suffix && <span className={styles.suffix}>{row.suffix}</span>}
        {expandable && <RightOutlined className={styles.chevron} />}
      </button>
      {open && expandable && (
        <div className={styles.body}>
          <Body row={row} />
        </div>
      )}
    </div>
  );
}
