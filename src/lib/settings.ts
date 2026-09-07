import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { ReaderId } from "./readers";

type SettingsState = {
  readerId: ReaderId;
  autoOpen: boolean;
  setReaderId: (id: ReaderId) => void;
  setAutoOpen: (value: boolean) => void;
};

export const useSettings = create<SettingsState>()(
  persist(
    (set) => ({
      readerId: "share",
      autoOpen: false,
      setReaderId: (readerId) => set({ readerId }),
      setAutoOpen: (autoOpen) => set({ autoOpen }),
    }),
    { name: "chengshu-settings-v4" },
  ),
);
