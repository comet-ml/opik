import noop from "lodash/noop";

import { DashboardState } from "@/types/dashboard";

export interface DashboardLocalEntry {
  dashboardId: string;
  config: DashboardState;
  savedAt: number;
}

const DB_NAME = "OpikDashboards";
const DB_VERSION = 1;
const STORE_NAME = "dashboards";

let dbPromise: Promise<IDBDatabase> | null = null;

const getDb = (): Promise<IDBDatabase> => {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        request.result.createObjectStore(STORE_NAME, {
          keyPath: "dashboardId",
        });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => {
        dbPromise = null;
        reject(request.error);
      };
    });
  }
  return dbPromise;
};

export const saveLocal = (
  dashboardId: string,
  config: DashboardState,
): Promise<void> =>
  getDb()
    .then(
      (db) =>
        new Promise<void>((resolve, reject) => {
          const tx = db.transaction(STORE_NAME, "readwrite");
          tx.objectStore(STORE_NAME).put({
            dashboardId,
            config,
            savedAt: Date.now(),
          });
          tx.oncomplete = () => resolve();
          tx.onerror = () => reject(tx.error);
        }),
    )
    .catch(noop);

export const loadLocal = (
  dashboardId: string,
): Promise<DashboardLocalEntry | undefined> =>
  getDb()
    .then(
      (db) =>
        new Promise<DashboardLocalEntry | undefined>((resolve, reject) => {
          const tx = db.transaction(STORE_NAME, "readonly");
          const request = tx.objectStore(STORE_NAME).get(dashboardId);
          request.onsuccess = () => resolve(request.result ?? undefined);
          request.onerror = () => reject(request.error);
        }),
    )
    .catch(() => undefined);

export const clearLocal = (dashboardId: string): Promise<void> =>
  getDb()
    .then(
      (db) =>
        new Promise<void>((resolve, reject) => {
          const tx = db.transaction(STORE_NAME, "readwrite");
          tx.objectStore(STORE_NAME).delete(dashboardId);
          tx.oncomplete = () => resolve();
          tx.onerror = () => reject(tx.error);
        }),
    )
    .catch(noop);
