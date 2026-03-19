declare var NativeModules: {
  TamerInsetsModule?: {
    getInsets(callback: (res: { top: number; right: number; bottom: number; left: number }) => void): void
    getKeyboard(callback: (res: { visible: boolean; height: number; duration: number }) => void): void
  }
}
