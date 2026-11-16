import { filterMenusByAdminVip, LOG_CENTER_MENU, withLogCenterMenu } from '../menuConfig';

jest.mock('@/pages/manager/service/session', () => ({ getDcSystemConfig: jest.fn() }));

it('hides configured and built-in log management entries for commercial, including adminvip', () => {
  const existing = { path: '/manager/systemParams/sandbox', name: '沙箱配置管理' };
  const configuredLogMenu = { path: '/manager/logs', name: '自定义日志名称' };
  const menus = withLogCenterMenu([LOG_CENTER_MENU, existing, configuredLogMenu], 'commercial');
  expect(filterMenusByAdminVip(menus, true)).toEqual([{ ...existing, routes: undefined }]);
});

it.each(['openSource', 'other', '', null, undefined])('preserves the log menu for version %s', (brandVersion) => {
  expect(withLogCenterMenu([], brandVersion)).toEqual([LOG_CENTER_MENU]);
});

it('adds one log menu after existing menus and restricts it to adminvip', () => {
  const existing = { path: '/manager/workgroup-templates', name: '工作组模板管理' };
  const menus = withLogCenterMenu([LOG_CENTER_MENU, existing]);
  expect(menus).toEqual([existing, LOG_CENTER_MENU]);
  expect(filterMenusByAdminVip(menus, false).map((item) => item.path)).toEqual([existing.path]);
  expect(filterMenusByAdminVip(menus, true).map((item) => item.path)).toEqual([existing.path, '/manager/logs']);
});
