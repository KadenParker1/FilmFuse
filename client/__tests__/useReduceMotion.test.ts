import { AccessibilityInfo } from 'react-native';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { useReduceMotion } from '../lib/useReduceMotion';

type Listener = (value: boolean) => void;

describe('useReduceMotion', () => {
  let listener: Listener | undefined;
  const remove = jest.fn();

  beforeEach(() => {
    listener = undefined;
    remove.mockClear();
    jest.spyOn(AccessibilityInfo, 'addEventListener').mockImplementation(((_event: string, fn: Listener) => {
      listener = fn;
      return { remove };
    }) as never);
  });
  afterEach(() => jest.restoreAllMocks());

  it('starts false, then takes the OS setting', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(true);
    const { result } = await renderHook(() => useReduceMotion());
    await waitFor(() => expect(result.current).toBe(true));
    expect(AccessibilityInfo.addEventListener).toHaveBeenCalledWith('reduceMotionChanged', expect.any(Function));
  });

  it('follows changes and unsubscribes on unmount', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(false);
    const { result, unmount } = await renderHook(() => useReduceMotion());
    expect(result.current).toBe(false);
    await act(async () => listener!(true));
    expect(result.current).toBe(true);
    await act(async () => listener!(false));
    expect(result.current).toBe(false);
    await unmount();
    expect(remove).toHaveBeenCalled();
  });

  it('stays false when the setting cannot be read', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockRejectedValue(new Error('unsupported'));
    const { result } = await renderHook(() => useReduceMotion());
    await act(async () => {});
    expect(result.current).toBe(false);
  });

  it('ignores a late answer after unmount', async () => {
    let resolve!: (v: boolean) => void;
    jest
      .spyOn(AccessibilityInfo, 'isReduceMotionEnabled')
      .mockReturnValue(new Promise<boolean>((r) => (resolve = r)));
    const { result, unmount } = await renderHook(() => useReduceMotion());
    await unmount();
    await act(async () => resolve(true));
    expect(result.current).toBe(false);
  });
});
