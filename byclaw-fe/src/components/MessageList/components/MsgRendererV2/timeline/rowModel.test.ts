import { SSEMessageType } from '@/constants/message';
import type { IMessageListItem } from '@/typescript/message';
import { isContextItem, isRowItem, toTimelineRow } from './rowModel';

const tool = (substance: Record<string, unknown>, status = '_DONE_'): IMessageListItem =>
  ({
    seq: 1,
    contentType: SSEMessageType.toolCall,
    status,
    content: { substance: { status, ...substance } },
  } as IMessageListItem);

const think = (text: string): IMessageListItem =>
  ({ seq: 1, contentType: SSEMessageType.thinkText, content: { substance: text } } as IMessageListItem);

describe('timeline row model', () => {
  it('heads a thinking row with its first line and keeps the full text', () => {
    const row = toTimelineRow(think('The doc looks good.\nOne small fix: line 4.'), false);
    expect(row).toMatchObject({
      variant: 'think',
      titleKey: 'timeline.title.think',
      summary: 'The doc looks good.',
      state: 'ok',
    });
    expect(row?.text).toContain('One small fix');
    expect(toTimelineRow(think('   '), false)).toBeUndefined();
  });

  it('summarises bash by its description, else its command, and shows the command when expanded', () => {
    expect(
      toTimelineRow(
        tool({ title: 'bash', input: '{"command":"ls /opt","description":"Verify header"}', output: 'byclaw' }),
        false
      )
    ).toMatchObject({ variant: 'bash', summary: 'Verify header', input: 'ls /opt', output: 'byclaw', state: 'ok' });
    expect(toTimelineRow(tool({ title: 'bash', input: '{"command":"ls /opt\\nwc -l"}' }), false)?.summary).toBe(
      'ls /opt'
    );
  });

  it('names the file of a read, write or edit and counts the changed lines', () => {
    expect(toTimelineRow(tool({ title: 'read', input: '{"file_path":"e2e/README.md"}' }), false)).toMatchObject({
      variant: 'read',
      titleKey: 'timeline.title.read',
      summary: 'e2e/README.md',
    });
    expect(
      toTimelineRow(tool({ title: 'write', input: '{"file_path":"a.md","content":"x\\ny\\nz"}' }), false)
    ).toMatchObject({ variant: 'write', summary: 'a.md', suffix: '+3' });
    const edit = toTimelineRow(
      tool({ title: 'edit', input: '{"file_path":"a.md","old_string":"one","new_string":"two\\nthree"}' }),
      false
    );
    expect(edit).toMatchObject({ variant: 'edit', summary: 'a.md', suffix: '+2 −1' });
    expect(edit?.diff).toEqual({ removed: ['one'], added: ['two', 'three'] });
  });

  it('shows a file inside the session or project workspace relative to it', () => {
    expect(
      toTimelineRow(
        tool({
          title: 'edit',
          input: '{"file_path":"/by/.sessions/10002821/survey/02.md","old_string":"a","new_string":"b"}',
        }),
        false
      )?.summary
    ).toBe('survey/02.md');
    expect(
      toTimelineRow(tool({ title: 'read', input: '{"file_path":"/by/projects/-1/README.md"}' }), false)?.summary
    ).toBe('README.md');
    expect(toTimelineRow(tool({ title: 'read', input: '{"file_path":"/opt/byclaw/x.md"}' }), false)?.summary).toBe(
      '/opt/byclaw/x.md'
    );
  });

  it('titles an unclassified tool by its name and summarises its first string argument', () => {
    expect(
      toTimelineRow(
        tool({ title: 'devflow_transition', input: '{"expectedRevision":2,"id":"0001-survey","to":"designing"}' }),
        false
      )
    ).toMatchObject({ variant: 'others', title: 'devflow_transition', summary: '0001-survey' });
  });

  it('shows the active todo, the subagent task and the delivered file names', () => {
    expect(
      toTimelineRow(
        tool({
          title: 'todo_write',
          input: JSON.stringify({
            todos: [
              { content: 'done', status: 'completed' },
              { content: '调研 0003', status: 'in_progress' },
            ],
          }),
        }),
        false
      )?.summary
    ).toBe('调研 0003');
    expect(
      toTimelineRow(tool({ title: 'subagent', input: '{"description":"独立核验","prompt":"..."}' }), false)
    ).toMatchObject({ variant: 'subagent', summary: '独立核验' });
    expect(
      toTimelineRow(
        tool({ title: 'present', input: '{"files":[{"path":"/by/x/01.md"},{"path":"/by/x/02.md"}]}' }),
        false
      )
    ).toMatchObject({ variant: 'deliver', summary: '01.md, 02.md' });
  });

  it('reports a running call while its result has not arrived and a failed one as an error', () => {
    expect(toTimelineRow(tool({ title: 'bash', input: '{"command":"sleep 5"}' }, '_START_'), true)?.state).toBe(
      'running'
    );
    expect(toTimelineRow(tool({ title: 'bash', input: '{}' }, '_ERROR_'), false)?.state).toBe('error');
  });

  it('leaves runtime context and team snapshots to other renderers', () => {
    const context = tool({ title: '上下文注入 · plugin:byclaw-context', eventKind: 'context', source: 'runtime' });
    expect(isContextItem(context)).toBe(true);
    expect(isRowItem(context)).toBe(false);
    const team = tool({ schemaVersion: 2, eventKind: 'agent-teams/snapshot', team: {} });
    expect(isContextItem(team)).toBe(false);
    expect(isRowItem(team)).toBe(false);
    expect(toTimelineRow(team, false)).toBeUndefined();
  });
});
