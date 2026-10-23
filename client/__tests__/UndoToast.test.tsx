jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { AccessibilityInfo, AppState, Pressable, Text } from 'react-native';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { useUndismissPendingRating } from '../api/pendingRatings';
import { useUndismissRecommendation } from '../api/recommendations';
import { Toast, UNDO_TOAST_MS, UndoToastProvider, useUndoToast, type UndoOptions } from '../components/ui';
import { UNDO_FAILED_MS, UNDO_FAILED_MESSAGE } from '../components/ui/UndoToast';
import { queryKeys } from '../lib/queryKeys';
import { makeTasteProfile } from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { TasteProfileDTO } from '../types/api';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
});
afterEach(() => jest.useRealTimers());

function Trigger({ options }: { options: UndoOptions }) {
  const { showUndo, hideUndo, visible } = useUndoToast();
  return (
    <>
      <Pressable accessibilityRole="button" accessibilityLabel="do it" onPress={() => showUndo(options)}>
        <Text>do it</Text>
      </Pressable>
      <Pressable accessibilityRole="button" accessibilityLabel="hide" onPress={() => hideUndo()}>
        <Text>hide</Text>
      </Pressable>
      <Text testID="visible">{String(visible)}</Text>
    </>
  );
}

const show = async (options: Partial<UndoOptions> = {}) => {
  const onUndo = options.onUndo ?? jest.fn();
  await render(
    <UndoToastProvider>
      <Trigger options={{ message: 'Removed', onUndo, ...options }} />
    </UndoToastProvider>,
  );
  await fireEvent.press(screen.getByRole('button', { name: 'do it' }));
  return onUndo;
};
const advance = async (ms: number) => {
  await act(async () => {
    jest.advanceTimersByTime(ms);
  });
};

describe('Toast with an action', () => {
  it('renders the button with its own label; touches pass around the pill', async () => {
    const onPress = jest.fn();
    await render(<Toast message="Hidden" tone="info" action={{ label: 'Undo', accessibilityLabel: 'Undo hiding Heat', onPress }} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Undo hiding Heat' }));
    expect(onPress).toHaveBeenCalled();
    expect(screen.getByTestId('toast')).toHaveTextContent('HiddenUndo');
    expect(screen.getByTestId('toast').parent).toHaveProp('pointerEvents', 'box-none');
  });

  it('without an action it never takes touches', async () => {
    await render(<Toast message="Saved" />);
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByTestId('toast')).toBeOnTheScreen();
  });
});

describe('UndoToastProvider', () => {
  it('shows the message with Undo, announces it, and Undo runs once and hides it', async () => {
    const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility');
    const onUndo = await show({ undoAccessibilityLabel: 'Undo removing Heat' });
    expect(screen.getByTestId('undo-toast')).toHaveTextContent('RemovedUndo');
    expect(screen.getByTestId('undo-toast')).toHaveProp('accessibilityLiveRegion', 'polite');
    expect(screen.getByTestId('visible')).toHaveTextContent('true');
    expect(announce).toHaveBeenCalledWith('Removed. Undo available.');
    await fireEvent.press(screen.getByRole('button', { name: 'Undo removing Heat' }));
    expect(onUndo).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('undo-toast')).toBeNull();
    expect(screen.getByTestId('visible')).toHaveTextContent('false');
  });

  it('auto-dismisses after the undo window without undoing', async () => {
    jest.useFakeTimers();
    const onUndo = await show();
    await advance(UNDO_TOAST_MS - 1);
    expect(screen.getByTestId('undo-toast')).toBeOnTheScreen();
    await advance(1);
    expect(screen.queryByTestId('undo-toast')).toBeNull();
    expect(onUndo).not.toHaveBeenCalled();
  });

  it('a new toast replaces the old one and restarts the timer', async () => {
    jest.useFakeTimers();
    await show();
    await advance(UNDO_TOAST_MS - 1000);
    await fireEvent.press(screen.getByRole('button', { name: 'do it' }));
    await advance(2000);
    expect(screen.getByTestId('undo-toast')).toBeOnTheScreen();
    await advance(UNDO_TOAST_MS);
    expect(screen.queryByTestId('undo-toast')).toBeNull();
  });

  it('hideUndo hides it; a custom label works', async () => {
    await show({ undoLabel: 'Bring back' });
    expect(screen.getByRole('button', { name: 'Bring back' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'hide' }));
    expect(screen.queryByTestId('undo-toast')).toBeNull();
  });

  it.each([
    ['rejects', () => Promise.reject(new Error('down'))],
    ['throws', () => {
      throw new Error('down');
    }],
  ])('says so when the undo %s, then goes away', async (_how, onUndo) => {
    await show({ onUndo });
    jest.useFakeTimers();
    await fireEvent.press(screen.getByRole('button', { name: 'Undo' }));
    await waitFor(() => expect(screen.getByTestId('undo-toast')).toHaveTextContent(UNDO_FAILED_MESSAGE));
    expect(screen.queryByRole('button', { name: 'Undo' })).toBeNull();
    await advance(UNDO_FAILED_MS);
    expect(screen.queryByTestId('undo-toast')).toBeNull();
  });

  it('a double tap on Undo undoes once', async () => {
    const onUndo = await show();
    const button = screen.getByRole('button', { name: 'Undo' });
    // Pressability's click handler, called twice before the re-render that hides the button.
    const click = button.props.onClick as (e: object) => void;
    const event = { nativeEvent: {}, persist: () => {}, stopPropagation: () => {} };
    await act(async () => {
      click(event);
      click(event);
    });
    expect(onUndo).toHaveBeenCalledTimes(1);
  });

  it('a failed undo does not cover a newer toast', async () => {
    let reject: (e: Error) => void = () => {};
    const onUndo = jest.fn(() => new Promise((_resolve, r) => (reject = r)));
    await show({ onUndo });
    await fireEvent.press(screen.getByRole('button', { name: 'Undo' }));
    // Another action shows its own Undo while the first undo is still in flight.
    await fireEvent.press(screen.getByRole('button', { name: 'do it' }));
    await act(async () => {
      reject(new Error('down'));
    });
    expect(screen.getByTestId('undo-toast')).toHaveTextContent('RemovedUndo');
    expect(screen.getByRole('button', { name: 'Undo' })).toBeOnTheScreen();
  });

  describe('deferred actions (onCommit)', () => {
    it('commits once when the window expires', async () => {
      jest.useFakeTimers();
      const onCommit = jest.fn();
      await show({ onCommit });
      await advance(UNDO_TOAST_MS);
      await advance(UNDO_TOAST_MS);
      expect(onCommit).toHaveBeenCalledTimes(1);
      expect(onCommit).toHaveBeenCalledWith('expired');
    });

    it('never commits after Undo', async () => {
      jest.useFakeTimers();
      const onCommit = jest.fn();
      const onUndo = await show({ onCommit });
      await fireEvent.press(screen.getByRole('button', { name: 'Undo' }));
      await advance(UNDO_TOAST_MS * 2);
      expect(onUndo).toHaveBeenCalled();
      expect(onCommit).not.toHaveBeenCalled();
    });

    it('commits when another toast replaces it, and when hidden', async () => {
      const onCommit = jest.fn();
      await show({ onCommit });
      await fireEvent.press(screen.getByRole('button', { name: 'do it' }));
      expect(onCommit).toHaveBeenCalledWith('replaced');
      onCommit.mockClear();
      await fireEvent.press(screen.getByRole('button', { name: 'hide' }));
      expect(onCommit).toHaveBeenCalledWith('hidden');
    });

    it('hideUndo(key) only hides that toast', async () => {
      const first = jest.fn();
      let firstKey = 0;
      function Keyed() {
        const { showUndo, hideUndo } = useUndoToast();
        return (
          <>
            <Pressable accessibilityRole="button" accessibilityLabel="first" onPress={() => (firstKey = showUndo({ message: 'One', onUndo: jest.fn(), onCommit: first }))}>
              <Text>first</Text>
            </Pressable>
            <Pressable accessibilityRole="button" accessibilityLabel="second" onPress={() => showUndo({ message: 'Two', onUndo: jest.fn() })}>
              <Text>second</Text>
            </Pressable>
            <Pressable accessibilityRole="button" accessibilityLabel="hide first" onPress={() => hideUndo(firstKey)}>
              <Text>hide first</Text>
            </Pressable>
          </>
        );
      }
      await render(
        <UndoToastProvider>
          <Keyed />
        </UndoToastProvider>,
      );
      await fireEvent.press(screen.getByRole('button', { name: 'first' }));
      await fireEvent.press(screen.getByRole('button', { name: 'second' }));
      await fireEvent.press(screen.getByRole('button', { name: 'hide first' }));
      expect(screen.getByTestId('undo-toast')).toHaveTextContent('TwoUndo');
      expect(first).toHaveBeenCalledTimes(1);
    });

    it('commits on unmount and when the app goes to the background', async () => {
      let onAppState: ((s: string) => void) | undefined;
      const spy = jest.spyOn(AppState, 'addEventListener').mockImplementation((_type, handler) => {
        onAppState = handler as (s: string) => void;
        return { remove: jest.fn() } as unknown as ReturnType<typeof AppState.addEventListener>;
      });
      const onCommit = jest.fn();
      await show({ onCommit });
      await act(async () => {
        onAppState?.('active');
      });
      expect(onCommit).not.toHaveBeenCalled();
      await act(async () => {
        onAppState?.('background');
      });
      expect(onCommit).toHaveBeenCalledWith('background');
      expect(screen.queryByTestId('undo-toast')).toBeNull();

      onCommit.mockClear();
      await fireEvent.press(screen.getByRole('button', { name: 'do it' }));
      await screen.unmount();
      expect(onCommit).toHaveBeenCalledWith('unmount');
      spy.mockRestore();
    });

    it('a throwing commit does not break the toast', async () => {
      await show({
        onCommit: () => {
          throw new Error('boom');
        },
      });
      await fireEvent.press(screen.getByRole('button', { name: 'do it' }));
      expect(screen.getByTestId('undo-toast')).toHaveTextContent('RemovedUndo');
    });

    it('showError shows a message with no button and commits what was up', async () => {
      const onCommit = jest.fn();
      function Err() {
        const { showUndo, showError } = useUndoToast();
        return (
          <>
            <Pressable accessibilityRole="button" accessibilityLabel="act" onPress={() => showUndo({ message: 'Did', onUndo: jest.fn(), onCommit })}>
              <Text>act</Text>
            </Pressable>
            <Pressable accessibilityRole="button" accessibilityLabel="fail" onPress={() => showError('It failed')}>
              <Text>fail</Text>
            </Pressable>
          </>
        );
      }
      await render(
        <UndoToastProvider>
          <Err />
        </UndoToastProvider>,
      );
      await fireEvent.press(screen.getByRole('button', { name: 'act' }));
      await fireEvent.press(screen.getByRole('button', { name: 'fail' }));
      expect(onCommit).toHaveBeenCalledWith('replaced');
      expect(screen.getByTestId('undo-toast')).toHaveTextContent('It failed');
      expect(screen.queryByRole('button', { name: 'Undo' })).toBeNull();
    });
  });

  it('useUndoToast throws outside the provider', async () => {
    const spy = jest.spyOn(console, 'error').mockImplementation(() => {});
    await expect(render(<Trigger options={{ message: 'x', onUndo: jest.fn() }} />)).rejects.toThrow(
      'useUndoToast must be used inside <UndoToastProvider>',
    );
    spy.mockRestore();
  });
});

describe('undo API hooks', () => {
  it('useUndismissRecommendation DELETEs the dismissal, refetches For you and marks taste rebuilding', async () => {
    fetchMock.mockResolvedValue(jsonResponse(204, undefined));
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.tasteProfile, makeTasteProfile({ status: 'FRESH' }));
    const invalidate = jest.spyOn(client, 'invalidateQueries');
    const { result } = await renderHookWithClient(() => useUndismissRecommendation(), client);
    await act(async () => {
      await result.current.mutateAsync(42);
    });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://localhost:8080/api/v1/me/recommendations/42/dismiss');
    expect(init.method).toBe('DELETE');
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.recommendations });
    expect(client.getQueryData<TasteProfileDTO>(queryKeys.tasteProfile)?.status).toBe('REBUILDING');
  });

  it('useUndismissPendingRating DELETEs the dismissal and refetches the prompts', async () => {
    fetchMock.mockResolvedValue(jsonResponse(204, undefined));
    const client = createTestQueryClient();
    const invalidate = jest.spyOn(client, 'invalidateQueries');
    const { result } = await renderHookWithClient(() => useUndismissPendingRating(), client);
    await act(async () => {
      await result.current.mutateAsync(7);
    });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://localhost:8080/api/v1/me/pending-ratings/7/dismiss');
    expect(init.method).toBe('DELETE');
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.pendingRatings });
  });
});
