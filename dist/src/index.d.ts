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
export declare const TAMER_INSETS_SNAPSHOT_GLOBAL_KEY = "__tamerInsetsSnapshot";
/** Prime the shared insets cache (e.g. from native before first React paint). */
export declare function seedTamerInsets(raw: Insets): void;
export declare function useInsets(): InsetsWithRaw;
export declare function useKeyboard(): KeyboardStateWithRaw;
//# sourceMappingURL=index.d.ts.map