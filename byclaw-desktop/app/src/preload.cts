/**
 * Bridge for ByClaw's own local pages (setup, settings, audit). Never loaded into the remote web
 * page; main additionally checks that every call comes from a file:// page in static/.
 */

import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('byclawLocal', {
  getState: () => ipcRenderer.invoke('byclaw:getState'),
  saveWebUrl: (url: string, extraOrigins: string[], loginUrl: string) => ipcRenderer.invoke('byclaw:saveWebUrl', url, extraOrigins, loginUrl),
  rename: (name: string) => ipcRenderer.invoke('byclaw:rename', name),
  addDir: () => ipcRenderer.invoke('byclaw:addDir'),
  removeDir: (dir: string) => ipcRenderer.invoke('byclaw:removeDir', dir),
  setLaunchAtLogin: (value: boolean) => ipcRenderer.invoke('byclaw:setLaunchAtLogin', value),
  auditRecent: () => ipcRenderer.invoke('byclaw:auditRecent'),
  openAuditFolder: () => ipcRenderer.invoke('byclaw:openAuditFolder'),
  openExtensionDir: () => ipcRenderer.invoke('byclaw:openExtensionDir'),
  copyExtensionDir: () => ipcRenderer.invoke('byclaw:copyExtensionDir'),
  onState: (listener: (state: unknown) => void) => {
    ipcRenderer.on('byclaw:state', (_event, state) => listener(state))
  },
})
