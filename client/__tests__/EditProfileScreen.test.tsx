jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/me', () => ({ useMe: jest.fn(), useUpdateMe: jest.fn() }));
jest.mock('../api/profilePhoto', () => ({ useUploadProfilePhoto: jest.fn(), useRemoveProfilePhoto: jest.fn() }));
jest.mock('../api/usernames', () => ({ ...jest.requireActual('../api/usernames'), useUsernameAvailability: jest.fn() }));
let mockCanUseCamera = true;
jest.mock('../components/profile/profilePhoto', () => ({
  get canUseCamera() {
    return mockCanUseCamera;
  },
  pickSquarePhoto: jest.fn(),
  prepareProfilePhoto: jest.fn(),
}));

import { act, fireEvent, render, screen } from '@testing-library/react-native';
import EditProfileScreen from '../app/profile/edit';
import { useMe, useUpdateMe } from '../api/me';
import { useRemoveProfilePhoto, useUploadProfilePhoto } from '../api/profilePhoto';
import { useUsernameAvailability } from '../api/usernames';
import { pickSquarePhoto, prepareProfilePhoto } from '../components/profile/profilePhoto';
import { ApiError, NetworkError } from '../lib/apiClient';
import { makeProfile } from '../test-utils/fixtures';
import { queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';

const mockUseMe = useMe as jest.Mock;
const mockUseUpdate = useUpdateMe as jest.Mock;
const mockUpload = useUploadProfilePhoto as jest.Mock;
const mockRemove = useRemoveProfilePhoto as jest.Mock;
const mockAvailability = useUsernameAvailability as jest.Mock;
const mockPick = pickSquarePhoto as jest.Mock;
const mockPrepare = prepareProfilePhoto as jest.Mock;

type Mutation = { mutate: jest.Mock; mutateAsync: jest.Mock; isPending: boolean; isError: boolean; error: unknown };
const mutation = (): Mutation => ({
  mutate: jest.fn(),
  mutateAsync: jest.fn(() => Promise.resolve()),
  isPending: false,
  isError: false,
  error: null,
});
let update: Mutation;
let upload: Mutation;
let remove: Mutation;

beforeEach(() => {
  jest.useFakeTimers();
  routerState.reset();
  mockCanUseCamera = true;
  update = mutation();
  upload = mutation();
  remove = mutation();
  mockUseMe.mockReturnValue(queryResult({ data: makeProfile() }));
  mockUseUpdate.mockImplementation(() => update);
  mockUpload.mockImplementation(() => upload);
  mockRemove.mockImplementation(() => remove);
  mockAvailability.mockReturnValue(queryResult());
  mockPick.mockReset();
  mockPrepare.mockReset();
});

afterEach(() => jest.useRealTimers());

const typeUsername = async (u: string) => {
  await fireEvent.changeText(screen.getByLabelText('Username'), u);
  await act(async () => {
    jest.advanceTimersByTime(300);
  });
};

describe('EditProfileScreen', () => {
  it('prefills the form from /me', async () => {
    await render(<EditProfileScreen />);
    expect(screen.getByLabelText('Display name')).toHaveDisplayValue('Ada Lovelace');
    expect(screen.getByLabelText('Username')).toHaveDisplayValue('ada');
    expect(screen.getByLabelText('Bio')).toHaveDisplayValue('Loves heist films.');
    expect(screen.getByTestId('username-status')).toHaveTextContent('Your current username');
  });

  it('shows loading and error states', async () => {
    mockUseMe.mockReturnValue(queryResult());
    await render(<EditProfileScreen />);
    expect(screen.getByLabelText('Loading your profile')).toBeOnTheScreen();

    const refetch = jest.fn();
    mockUseMe.mockReturnValue(queryResult({ isError: true, isPending: false, error: new NetworkError(), refetch }));
    await screen.rerender(<EditProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('checks availability with the debounced username', async () => {
    await render(<EditProfileScreen />);
    await fireEvent.changeText(screen.getByLabelText('Username'), 'popcorn');
    expect(screen.getByTestId('username-status')).toHaveTextContent('Checking…');
    expect(mockAvailability).not.toHaveBeenCalledWith('popcorn', 'ada');
    await act(async () => {
      jest.advanceTimersByTime(300);
    });
    expect(mockAvailability).toHaveBeenLastCalledWith('popcorn', 'ada');
  });

  it.each([
    [{ data: { username: 'popcorn', valid: true, available: true } }, 'popcorn', 'Available'],
    [{ data: { username: 'popcorn', valid: true, available: false } }, 'popcorn', 'Already taken'],
    [{}, 'no', '3–30 characters'],
    [{ isError: true, isPending: false }, 'popcorn', "Couldn't check availability"],
  ])('shows availability state %#', async (state, u, text) => {
    mockAvailability.mockReturnValue(queryResult(state));
    await render(<EditProfileScreen />);
    await typeUsername(u);
    expect(screen.getByTestId('username-status')).toHaveTextContent(new RegExp(text));
  });

  it('blocks saving a taken username', async () => {
    mockAvailability.mockReturnValue(queryResult({ data: { username: 'taken', valid: true, available: false } }));
    await render(<EditProfileScreen />);
    await typeUsername('taken');
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  it('counts bio characters', async () => {
    await render(<EditProfileScreen />);
    expect(screen.getByTestId('bio-counter')).toHaveTextContent('18/300');
    await fireEvent.changeText(screen.getByLabelText('Bio'), 'x'.repeat(300));
    expect(screen.getByTestId('bio-counter')).toHaveTextContent('300/300');
  });

  it('saves only changed fields, then goes back', async () => {
    update.mutate.mockImplementation((_body, opts: { onSuccess: () => void }) => opts.onSuccess());
    mockAvailability.mockReturnValue(queryResult({ data: { username: 'popcorn', valid: true, available: true } }));
    await render(<EditProfileScreen />);
    await typeUsername('  Popcorn ');
    await fireEvent.changeText(screen.getByLabelText('Bio'), ' New bio ');
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    expect(update.mutate).toHaveBeenCalledWith({ username: 'popcorn', bio: 'New bio' }, expect.anything());
    expect(routerState.router.back).toHaveBeenCalled();
  });

  it('just goes back when nothing changed', async () => {
    await render(<EditProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    expect(update.mutate).not.toHaveBeenCalled();
    expect(routerState.router.back).toHaveBeenCalled();
  });

  it('shows server fieldErrors inline under their fields', async () => {
    update.isError = true;
    update.error = new ApiError(400, 'Validation failed', {
      username: 'Username may contain only lowercase letters, digits and underscores',
      bio: 'Bio cannot exceed 300 characters',
    });
    await render(<EditProfileScreen />);
    expect(screen.getByText('Username may contain only lowercase letters, digits and underscores')).toBeOnTheScreen();
    expect(screen.getByText('Bio cannot exceed 300 characters')).toBeOnTheScreen();
    expect(screen.queryByTestId('save-error')).toBeNull();
  });

  it('shows a general save error when there are no field errors', async () => {
    update.isError = true;
    update.error = new ApiError(409, 'Username already taken');
    await render(<EditProfileScreen />);
    expect(screen.getByTestId('save-error')).toHaveTextContent('Username already taken');
  });

  describe('photo', () => {
    it('picks from the library, resizes, and uploads', async () => {
      mockPick.mockResolvedValue({ uri: 'file:///picked.jpg' });
      mockPrepare.mockResolvedValue('file:///resized.jpg');
      await render(<EditProfileScreen />);
      await fireEvent.press(screen.getByRole('button', { name: 'Change photo' }));
      await fireEvent.press(screen.getByRole('button', { name: 'Choose from library' }));
      await act(async () => {});
      expect(mockPick).toHaveBeenCalledWith('library');
      expect(mockPrepare).toHaveBeenCalledWith('file:///picked.jpg');
      expect(upload.mutateAsync).toHaveBeenCalledWith('file:///resized.jpg');
    });

    it('offers the camera on native', async () => {
      mockPick.mockResolvedValue({ cancelled: true });
      await render(<EditProfileScreen />);
      await fireEvent.press(screen.getByRole('button', { name: 'Change photo' }));
      await fireEvent.press(screen.getByRole('button', { name: 'Take photo' }));
      await act(async () => {});
      expect(mockPick).toHaveBeenCalledWith('camera');
      expect(upload.mutateAsync).not.toHaveBeenCalled();
    });

    it('goes straight to the file chooser on web and offers Remove inline', async () => {
      mockCanUseCamera = false;
      mockUseMe.mockReturnValue(queryResult({ data: makeProfile({ profileImageUrl: 'https://x/a.jpg' }) }));
      mockPick.mockResolvedValue({ cancelled: true });
      await render(<EditProfileScreen />);
      await fireEvent.press(screen.getByRole('button', { name: 'Change photo' }));
      await act(async () => {});
      expect(mockPick).toHaveBeenCalledWith('library');
      expect(screen.queryByRole('button', { name: 'Take photo' })).toBeNull();
      await fireEvent.press(screen.getByRole('button', { name: 'Remove photo' }));
      await act(async () => {});
      expect(remove.mutateAsync).toHaveBeenCalled();
    });

    it('shows picker and upload errors inline', async () => {
      mockPick.mockResolvedValue({ error: 'FilmFuse needs camera access' });
      await render(<EditProfileScreen />);
      await fireEvent.press(screen.getByRole('button', { name: 'Change photo' }));
      await fireEvent.press(screen.getByRole('button', { name: 'Take photo' }));
      await act(async () => {});
      expect(screen.getByTestId('photo-error')).toHaveTextContent('FilmFuse needs camera access');

      mockPick.mockResolvedValue({ uri: 'file:///p.jpg' });
      mockPrepare.mockResolvedValue('file:///r.jpg');
      upload.mutateAsync.mockRejectedValue(new ApiError(400, 'Path must end with .jpg'));
      await fireEvent.press(screen.getByRole('button', { name: 'Change photo' }));
      await fireEvent.press(screen.getByRole('button', { name: 'Choose from library' }));
      await act(async () => {});
      expect(screen.getByTestId('photo-error')).toHaveTextContent('Path must end with .jpg');
    });

    it('removes the photo from the native menu', async () => {
      mockUseMe.mockReturnValue(queryResult({ data: makeProfile({ profileImageUrl: 'https://x/a.jpg' }) }));
      await render(<EditProfileScreen />);
      await fireEvent.press(screen.getByRole('button', { name: 'Change photo' }));
      await fireEvent.press(screen.getByRole('button', { name: 'Remove photo' }));
      await act(async () => {});
      expect(remove.mutateAsync).toHaveBeenCalled();
    });
  });
});
