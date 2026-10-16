import { fireEvent, render, screen } from '@testing-library/react';
import { IMessageState, SSEMessageType } from '@/constants/message';
import type { IMessage, IMessageListItem } from '@/typescript/message';
import Timeline from './index';

jest.mock('@umijs/max', () => ({
  useIntl: () => ({ formatMessage: ({ id }: { id: string }) => id }),
}));

jest.mock('@/components/MessageList/lazyHandler', () => ({
  __esModule: true,
  default: { lazyComp: (type: string) => () => <div data-testid={`own-renderer-${type}`} /> },
}));

const items: IMessageListItem[] = [
  {
    seq: 1,
    contentType: SSEMessageType.toolCall,
    status: '_DONE_',
    content: { substance: { title: '上下文注入 · agent-instructions', eventKind: 'context', status: '_DONE_' } },
  },
  { seq: 2, contentType: SSEMessageType.thinkText, content: { substance: 'Check the header first.' } },
  {
    seq: 3,
    contentType: SSEMessageType.toolCall,
    status: '_DONE_',
    content: {
      substance: {
        title: 'bash',
        input: '{"command":"head -1 a.md","description":"Verify header"}',
        output: '# A',
        status: '_DONE_',
      },
    },
  },
  { seq: 4, contentType: SSEMessageType.askUserQuestions, content: { substance: {} } },
] as IMessageListItem[];

const message = { msgId: 'm1', messageState: IMessageState.Done, thinkList: items } as IMessage;

describe('Timeline', () => {
  it('draws thinking and tool calls one per line, hides runtime context, keeps other items', () => {
    render(<Timeline items={items} message={message} live={false} updateMessage={jest.fn()} />);
    expect(screen.queryByText(/上下文注入/)).toBeNull();
    expect(screen.getByText('timeline.title.think')).toBeTruthy();
    expect(screen.getByText('Check the header first.')).toBeTruthy();
    expect(screen.getByText('Verify header')).toBeTruthy();
    expect(screen.getByTestId(`own-renderer-${SSEMessageType.askUserQuestions}`)).toBeTruthy();
  });

  it('expands a bash row to its command and output', () => {
    render(<Timeline items={items} message={message} live={false} updateMessage={jest.fn()} />);
    expect(screen.queryByText('$ head -1 a.md')).toBeNull();
    fireEvent.click(screen.getByText('Verify header'));
    expect(screen.getByText('$ head -1 a.md')).toBeTruthy();
    expect(screen.getByText('# A')).toBeTruthy();
  });
});
