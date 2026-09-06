import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { ReaderId } from "./readers";

type SettingsState = {
  readerId: ReaderId;
  autoShare: boolean;
  autoDownload: boolean;
  setReaderId: (id: ReaderId) => void;
  setAutoShare: (value: boolean) => void;
  setAutoDownload: (value: boolean) => void;
};

export const useSettings = create<SettingsState>()(
  persist(
    (set) => ({
      readerId: "share",
      autoShare: true,
      autoDownload: true,
      setReaderId: (readerId) => set({ readerId }),
      setAutoShare: (autoShare) => set({ autoShare }),
      setAutoDownload: (autoDownload) => set({ autoDownload }),
    }),
    { name: "chengshu-settings" },
  ),
);
