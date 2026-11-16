import { isSandboxTabVisible } from '../tabVisibility';

const resourceTabs = ['TENANT', 'USER', 'SHARED', 'RECOVERY'];
const configTabs = ['spec', 'profile', 'health', 'elastic', 'shared'];

describe('sandbox tabs by brand version', () => {
  it('keeps only user resources and base config for commercial', () => {
    expect(resourceTabs.filter((key) => isSandboxTabVisible('commercial', key))).toEqual(['USER']);
    expect(configTabs.filter((key) => isSandboxTabVisible('commercial', key))).toEqual(['spec']);
  });

  it.each(['openSource', 'other', '', null])('preserves existing tabs for %s', (brandVersion) => {
    const tabs = [...resourceTabs, ...configTabs];
    expect(tabs.filter((key) => isSandboxTabVisible(brandVersion, key))).toEqual(tabs);
  });
});
