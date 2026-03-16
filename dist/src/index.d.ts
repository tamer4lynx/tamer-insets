export interface Insets {
    top: number;
    right: number;
    bottom: number;
    left: number;
}
export interface InsetsWithRaw extends Insets {
    raw: Insets;
}
export interface KeyboardState {
    visible: boolean;
    height: number;
    duration: number;
}
export interface KeyboardStateWithRaw extends KeyboardState {
    raw: KeyboardState;
}
export declare function useInsets(): InsetsWithRaw;
export declare function useKeyboard(): KeyboardStateWithRaw;
//# sourceMappingURL=index.d.ts.map