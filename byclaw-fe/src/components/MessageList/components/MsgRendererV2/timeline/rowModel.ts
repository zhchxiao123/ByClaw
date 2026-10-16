/**
 * One-line timeline rows for a v2 message's thinking channel, after DSH's chat view
 * (packages/client/ui-tool tool-call-model): a tool call is classified by name into a row
 * variant, titled, and summarised from its arguments; thinking text is one row headed by its
 * first line. Runtime context items (context injection, request configuration) are not rows.
 */
import { SSEEventStatus, SSEMessageType } from '@/constants/message';
import type { IMessageListItem } from '@/typescript/message';

export type RowVariant =
  | 'think'
  | 'bash'
  | 'read'
  | 'search'
  | 'write'
  | 'edit'
  | 'web'
  | 'todo'
  | 'subagent'
  | 'skill'
  | 'deliver'
  | 'others';

export type RowState = 'running' | 'ok' | 'error';

export type TimelineRow = {
  variant: RowVariant;

  /** Locale key of the title, or the raw tool name for an unclassified tool. */
  titleKey?: string;
  title?: string;
  summary: string;

  /** Trailing fragment kept outside the truncated summary, e.g. `+9 −9`. */
  suffix?: string;
  state: RowState;

  /** Expanded body: the call input (formatted) and its output. */
  input?: string;
  output?: string;

  /** Full thinking text for a think row. */
  text?: string;

  /** Unified diff lines for an edit row. */
  diff?: { removed: string[]; added: string[] };
};

const TOOL_VARIANTS: Record<string, RowVariant> = {
  bash: 'bash',
  pwsh: 'bash',
  read: 'read',
  read_image: 'read',
  web_fetch: 'web',
  web_search: 'search',
  grep: 'search',
  glob: 'search',
  write: 'write',
  edit: 'edit',
  todo_write: 'todo',
  subagent: 'subagent',
  skill: 'skill',
  present: 'deliver',
};

const TITLE_KEYS: Partial<Record<RowVariant, string>> = {
  think: 'timeline.title.think',
  bash: 'timeline.title.bash',
  read: 'timeline.title.read',
  search: 'timeline.title.search',
  write: 'timeline.title.write',
  edit: 'timeline.title.edit',
  web: 'timeline.title.web',
  todo: 'timeline.title.todo',
  subagent: 'timeline.title.subagent',
  skill: 'timeline.title.skill',
  deliver: 'timeline.title.deliver',
};

/** Argument keys tried in order for the summary of each variant. */
const SUMMARY_KEYS: Partial<Record<RowVariant, string[]>> = {
  bash: ['description', 'command'],
  read: ['path', 'file_path', 'url'],
  web: ['url'],
  search: ['query', 'pattern', 'url'],
  write: ['path', 'file_path'],
  edit: ['path', 'file_path'],
  subagent: ['description', 'label', 'prompt'],
  skill: ['name'],
};

type ToolSubstance = {
  title?: string;
  input?: unknown;
  output?: unknown;
  status?: string;
  eventKind?: string;
  source?: string;
};

/** Variants whose summary is a file path. */
const FILE_VARIANTS = new Set<RowVariant>(['read', 'write', 'edit']);

/** A path inside the turn's session or project workspace is shown relative to it, as DSH does with cwd. */
export const workspacePath = (path: string) => path.replace(/^\/by\/(?:\.sessions|projects)\/[^/]+\//, '');

const firstLine = (text: string) => {
  const line = text.trim().split(/\r?\n/)[0] ?? '';
  return line.length > 200 ? `${line.slice(0, 200)}…` : line;
};

const parseArgs = (input: unknown): Record<string, unknown> | undefined => {
  if (input && typeof input === 'object' && !Array.isArray(input)) return input as Record<string, unknown>;
  if (typeof input !== 'string' || input.trim() === '') return undefined;
  try {
    const parsed = JSON.parse(input);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
};

const textOf = (value: unknown): string | undefined => {
  if (value === undefined || value === null) return undefined;
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
};

const pick = (args: Record<string, unknown>, keys: string[]) => {
  for (const key of keys) {
    const value = args[key];
    if (typeof value === 'string' && value.trim() !== '') return value;
  }
  return undefined;
};

const lineCount = (value: unknown) => (typeof value === 'string' && value !== '' ? value.split('\n').length : 0);

const stateOf = (status: unknown, running: boolean): RowState => {
  if (status === '_ERROR_') return 'error';
  if (status === SSEEventStatus.done) return 'ok';
  return running ? 'running' : 'ok';
};

const toolSubstance = (item: IMessageListItem): ToolSubstance | undefined => {
  const substance = item.content?.substance as unknown;
  if (substance && typeof substance === 'object') return substance as ToolSubstance;
  if (typeof substance !== 'string') return undefined;
  try {
    const parsed = JSON.parse(substance);
    return parsed && typeof parsed === 'object' ? parsed : { title: substance };
  } catch {
    return { title: substance };
  }
};

/** Runtime context the model received (injected context, request configuration): not a step of the turn. */
export const isContextItem = (item: IMessageListItem) =>
  `${item.contentType}` === `${SSEMessageType.toolCall}` && toolSubstance(item)?.eventKind === 'context';

/**
 * Whether the item is drawn as a timeline row: thinking text, or a plain tool call. A tool-call
 * item carrying an event kind (a team snapshot, runtime context) keeps its own renderer.
 */
export const isRowItem = (item: IMessageListItem) => {
  const type = `${item.contentType}`;
  if (type === `${SSEMessageType.thinkText}`) return true;
  return type === `${SSEMessageType.toolCall}` && toolSubstance(item)?.eventKind === undefined;
};

const summaryFor = (variant: RowVariant, toolName: string, args: Record<string, unknown> | undefined, raw: string) => {
  if (!args) return firstLine(raw);
  if (variant === 'todo' && Array.isArray(args.todos)) {
    const todos = args.todos as { status?: string; content?: string }[];
    const active = todos.find((todo) => todo.status === 'in_progress');
    return firstLine(active?.content ?? `${todos.length}`);
  }
  if (variant === 'deliver' && Array.isArray(args.files)) {
    return (args.files as { path?: string }[])
      .map((file) => (file.path ?? '').split('/').pop())
      .filter(Boolean)
      .join(', ');
  }
  const picked = pick(args, SUMMARY_KEYS[variant] ?? []);
  if (picked !== undefined) return firstLine(FILE_VARIANTS.has(variant) ? workspacePath(picked) : picked);
  for (const value of Object.values(args)) {
    if (typeof value === 'string' && value.trim() !== '') return firstLine(value);
  }
  return variant === 'others' ? '' : firstLine(toolName);
};

/**
 * The row for one thinking-channel item, or undefined when it is not drawn as a row.
 * @param item - a thinkList item (thinking text or tool call).
 * @param running - the item is the last one of a message still being answered.
 */
export const toTimelineRow = (item: IMessageListItem, running: boolean): TimelineRow | undefined => {
  const type = `${item.contentType}`;
  if (type === `${SSEMessageType.thinkText}`) {
    const text = typeof item.content?.substance === 'string' ? item.content.substance : '';
    if (text.trim() === '') return undefined;
    return {
      variant: 'think',
      titleKey: TITLE_KEYS.think,
      summary: firstLine(text),
      text,
      state: running ? 'running' : 'ok',
    };
  }
  if (type !== `${SSEMessageType.toolCall}`) return undefined;
  const substance = toolSubstance(item);
  if (!substance || substance.eventKind !== undefined) return undefined;
  const toolName = `${substance.title ?? ''}`;
  const variant = TOOL_VARIANTS[toolName] ?? 'others';
  const rawInput = textOf(substance.input) ?? '';
  const args = parseArgs(substance.input);
  const row: TimelineRow = {
    variant,
    ...(TITLE_KEYS[variant] ? { titleKey: TITLE_KEYS[variant] } : { title: toolName || undefined }),
    summary: summaryFor(variant, toolName, args, rawInput),
    state: stateOf(substance.status ?? item.status, running),
    input: rawInput === '' ? undefined : textOf(args ?? rawInput),
    output: textOf(substance.output),
  };
  if (variant === 'edit' && args) {
    const removed = typeof args.old_string === 'string' ? args.old_string.split('\n') : [];
    const added = typeof args.new_string === 'string' ? args.new_string.split('\n') : [];
    row.diff = { removed, added };
    row.suffix = `+${added.length} −${removed.length}`;
  }
  if (variant === 'write' && args) row.suffix = `+${lineCount(args.content)}`;
  if (variant === 'bash' && args && typeof args.command === 'string') row.input = args.command;
  return row;
};
