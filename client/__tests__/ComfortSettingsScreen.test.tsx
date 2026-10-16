jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/comfort', () => ({ useComfortSettings: jest.fn(), useUpdateComfortSettings: jest.fn() }));

import { fireEvent, render, screen } from '@testing-library/react-native';
import ComfortSettingsScreen from '../app/settings/comfort';
import { useComfortSettings, useUpdateComfortSettings } from '../api/comfort';
import { ApiError, NetworkError } from '../lib/apiClient';
import { makeComfort } from '../test-utils/fixtures';
import { mutationResult, queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';
import type { ComfortSettingsDTO } from '../types/api';

const mockComfort = useComfortSettings as jest.Mock;
const mockUpdate = useUpdateComfortSettings as jest.Mock;
let update: ReturnType<typeof mutationResult>;

beforeEach(() => {
  routerState.reset();
  update = mutationResult();
  mockUpdate.mockImplementation(() => update);
  mockComfort.mockReturnValue(queryResult({ data: makeComfort({ maxCertification: 'R', contentLimits: { SEXUAL: 'NONE' } }) }));
});

const save = () => screen.getByRole('button', { name: 'Save' });

describe('ComfortSettingsScreen', () => {
  it('shows the current settings and the group privacy note', async () => {
    await render(<ComfortSettingsScreen />);
    expect(screen.getByRole('header', { name: 'Comfort settings' })).toBeOnTheScreen();
    expect(screen.getByRole('radio', { name: 'R' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Sex & nudity: None' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Violence & gore: Strong' })).toBeChecked();
    expect(screen.getByLabelText('Allow movies with unknown ratings').props.value).toBe(false);
    expect(screen.getByTestId('comfort-privacy-note')).toHaveTextContent(
      'In groups, FilmFuse uses the most cautious settings of everyone present. Nobody sees whose settings set the limit.',
    );
    expect(save()).toBeDisabled();
  });

  it('saves the edited settings with a PUT of the whole DTO', async () => {
    await render(<ComfortSettingsScreen />);
    await fireEvent.press(screen.getByRole('radio', { name: 'PG-13' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Violence & gore: Mild' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Sex & nudity: Strong' }));
    await fireEvent(screen.getByLabelText('Allow movies with unknown ratings'), 'valueChange', true);
    expect(save()).toBeEnabled();
    await fireEvent.press(save());
    expect(update.mutate).toHaveBeenCalledWith(
      { maxCertification: 'PG_13', contentLimits: { VIOLENCE: 'MILD' }, allowUnrated: true },
      expect.anything(),
    );
  });

  it('confirms the save and goes back to clean', async () => {
    update.mutate.mockImplementation((body: ComfortSettingsDTO, opts: { onSuccess: (s: ComfortSettingsDTO) => void }) => {
      update.isSuccess = true;
      opts.onSuccess(body);
    });
    await render(<ComfortSettingsScreen />);
    await fireEvent.press(screen.getByRole('radio', { name: 'No limit' }));
    await fireEvent.press(save());
    expect(screen.getByText('Saved.')).toBeOnTheScreen();
    expect(save()).toBeDisabled();
  });

  it('setting a level back to its saved value is not a change', async () => {
    await render(<ComfortSettingsScreen />);
    await fireEvent.press(screen.getByRole('radio', { name: 'Language: Mild' }));
    expect(save()).toBeEnabled();
    await fireEvent.press(screen.getByRole('radio', { name: 'Language: Strong' }));
    expect(save()).toBeDisabled();
  });

  it('blocks invalid settings client-side', async () => {
    mockComfort.mockReturnValue(
      queryResult({ data: makeComfort({ contentLimits: { VIOLENCE: 'LOTS' } as unknown as ComfortSettingsDTO['contentLimits'] }) }),
    );
    await render(<ComfortSettingsScreen />);
    await fireEvent.press(screen.getByRole('radio', { name: 'G' }));
    await fireEvent.press(save());
    expect(screen.getByRole('alert')).toHaveTextContent(/content limits isn’t valid/);
    expect(update.mutate).not.toHaveBeenCalled();
  });

  it('shows server field errors, then other errors', async () => {
    update.isError = true;
    update.error = new ApiError(400, 'Validation failed', { maxCertification: 'Unknown certification' });
    const { rerender } = await render(<ComfortSettingsScreen />);
    expect(screen.getByRole('alert')).toHaveTextContent('Unknown certification');
    update.error = new NetworkError();
    await rerender(<ComfortSettingsScreen />);
    expect(screen.getByRole('alert')).toHaveTextContent(/Couldn't reach/);
  });

  it('editing clears a previous error', async () => {
    update.isError = true;
    update.error = new NetworkError();
    await render(<ComfortSettingsScreen />);
    await fireEvent.press(screen.getByRole('radio', { name: 'PG' }));
    expect(update.reset).toHaveBeenCalled();
  });

  it('shows a busy Save while saving', async () => {
    update.isPending = true;
    await render(<ComfortSettingsScreen />);
    expect(save()).toBeBusy();
  });

  it('loading', async () => {
    mockComfort.mockReturnValue(queryResult());
    await render(<ComfortSettingsScreen />);
    expect(screen.getByTestId('comfort-loading')).toBeOnTheScreen();
  });

  it('error with retry', async () => {
    const refetch = jest.fn();
    mockComfort.mockReturnValue(queryResult({ isPending: false, isError: true, error: new NetworkError(), refetch }));
    await render(<ComfortSettingsScreen />);
    expect(screen.getByText("Couldn't load your comfort settings")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('back falls back to Profile after a deep link', async () => {
    routerState.router.canGoBack.mockReturnValue(false);
    await render(<ComfortSettingsScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/profile');
  });
});
