jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/social', () => ({ useBlocks: jest.fn(), useUnblock: jest.fn() }));

import { fireEvent, render, screen } from '@testing-library/react-native';
import BlockedPeopleScreen from '../app/settings/blocked';
import { useBlocks, useUnblock } from '../api/social';
import { NetworkError } from '../lib/apiClient';
import { makeUser } from '../test-utils/fixtures';
import { mutationResult, queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';

const mockBlocks = useBlocks as jest.Mock;
const mockUnblock = useUnblock as jest.Mock;
let unblock: ReturnType<typeof mutationResult>;

beforeEach(() => {
  routerState.reset();
  unblock = mutationResult();
  mockUnblock.mockImplementation(() => unblock);
  mockBlocks.mockReturnValue(
    queryResult({
      data: [
        makeUser({ id: 'b1', displayName: 'Troll One', username: 'troll1', friendship: 'BLOCKED' }),
        makeUser({ id: 'b2', displayName: null, username: 'spam', friendship: 'BLOCKED' }),
      ],
    }),
  );
});

describe('BlockedPeopleScreen', () => {
  it('lists blocked people with Unblock', async () => {
    await render(<BlockedPeopleScreen />);
    expect(screen.getByRole('header', { name: 'Blocked people' })).toBeOnTheScreen();
    expect(screen.getByText('Troll One')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Unblock @spam' }));
    expect(unblock.mutate).toHaveBeenCalledWith('b2');
  });

  it('opens a profile from a row', async () => {
    await render(<BlockedPeopleScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Troll One, @troll1' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/user/b1');
  });

  it('shows the pending row as busy and errors inline', async () => {
    unblock.isPending = true;
    unblock.variables = 'b1';
    const { rerender } = await render(<BlockedPeopleScreen />);
    expect(screen.getByRole('button', { name: 'Unblock Troll One' })).toBeBusy();
    expect(screen.getByRole('button', { name: 'Unblock @spam' })).not.toBeBusy();
    unblock.isPending = false;
    unblock.isError = true;
    unblock.error = new NetworkError();
    await rerender(<BlockedPeopleScreen />);
    expect(screen.getByRole('alert')).toHaveTextContent(/Couldn't reach/);
  });

  it('loading', async () => {
    mockBlocks.mockReturnValue(queryResult());
    await render(<BlockedPeopleScreen />);
    expect(screen.getByTestId('blocked-loading')).toBeOnTheScreen();
  });

  it('empty', async () => {
    mockBlocks.mockReturnValue(queryResult({ data: [] }));
    await render(<BlockedPeopleScreen />);
    expect(screen.getByText('No one blocked')).toBeOnTheScreen();
  });

  it('error with retry', async () => {
    const refetch = jest.fn();
    mockBlocks.mockReturnValue(queryResult({ isError: true, isPending: false, error: new NetworkError(), refetch }));
    await render(<BlockedPeopleScreen />);
    expect(screen.getByText("Couldn't load blocked people")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('back falls back to the Profile tab after a deep link', async () => {
    routerState.router.canGoBack.mockReturnValue(false);
    await render(<BlockedPeopleScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/profile');
  });
});
