jest.mock('expo-router', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    ...require('../test-utils/router').expoRouterMock,
    Redirect: ({ href }: { href: string }) => <Text testID="redirect">{href}</Text>,
  };
});
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));
const mockSignOut = jest.fn(() => Promise.resolve());
jest.mock('../auth/useAuth', () => ({ useAuth: () => ({ signOut: mockSignOut }) }));
jest.mock('../api/usernames', () => ({ ...jest.requireActual('../api/usernames'), useUsernameAvailability: jest.fn() }));
jest.mock('../components/profile/profilePhoto', () => ({
  canUseCamera: false,
  pickSquarePhoto: jest.fn(),
  prepareProfilePhoto: jest.fn(),
}));
jest.mock('../components/social/shareInvite', () => ({ shareInvite: jest.fn(() => Promise.resolve('copied')) }));

import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import OnboardingIndex from '../app/(onboarding)/onboarding/index';
import OnboardingStepScreen from '../app/(onboarding)/onboarding/[step]';
import { useUsernameAvailability } from '../api/usernames';
import { pickSquarePhoto } from '../components/profile/profilePhoto';
import { queryKeys } from '../lib/queryKeys';
import { serveRoutes, type Route } from '../test-utils/fetchRoutes';
import {
  makeComfort,
  makeMovie,
  makeOnboardingState,
  makeProfile,
  makeTasteProfile,
  makeUser,
} from '../test-utils/fixtures';
import { queryResult } from '../test-utils/queryResults';
import { createTestQueryClient, renderWithClient } from '../test-utils/render';
import { routerState } from '../test-utils/router';
import type { OnboardingStateDTO, ProfileResponseDTO } from '../types/api';
import { slideTo } from '../test-utils/slider';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
const mockAvailability = useUsernameAvailability as jest.Mock;

const profile = makeProfile({
  username: 'ada',
  displayName: 'Ada Lovelace',
  onboardingStep: 1,
  onboardingCompleted: false,
  onboardingCompletedAt: null,
});
const GENRES = [
  { id: 28, name: 'Action' },
  { id: 35, name: 'Comedy' },
  { id: 18, name: 'Drama' },
  { id: 27, name: 'Horror' },
  { id: 878, name: 'Science Fiction' },
  { id: 10749, name: 'Romance' },
];
const MOODS = [
  { tag: 'mind-bending', label: 'Mind-bending' },
  { tag: 'cozy', label: 'Cozy' },
  { tag: 'laugh-out-loud', label: 'Laugh-out-loud' },
  { tag: 'edge-of-seat', label: 'Edge of your seat' },
  { tag: 'tearjerker', label: 'Tearjerker' },
  { tag: 'visually-stunning', label: 'Visually stunning' },
  { tag: 'dark-gritty', label: 'Dark & gritty' },
  { tag: 'feel-good', label: 'Feel-good' },
];

/** Routes for /me, /me/onboarding and the step PUT (which answers the state at the higher step). */
function base(state: OnboardingStateDTO, me: ProfileResponseDTO = profile): Route[] {
  return [
    { path: '/me', body: me },
    { path: '/me/onboarding', body: state },
    {
      method: 'PUT',
      path: '/me/onboarding/step',
      body: (b: unknown) => ({ ...state, step: Math.max(state.step, (b as { step: number }).step) }),
    },
  ];
}

async function open(step: number | string, state: OnboardingStateDTO, extra: Route[] = [], me: ProfileResponseDTO = profile) {
  routerState.params = { step: String(step) };
  const api = serveRoutes(fetchMock, [...extra, ...base(state, me)]);
  const client = createTestQueryClient();
  const utils = await renderWithClient(<OnboardingStepScreen />, client);
  await waitFor(() => expect(screen.queryByLabelText('Loading your progress')).toBeNull());
  return { api, ...utils };
}

const continueButton = (name = 'Continue') => screen.getByRole('button', { name });
const expectAdvancedTo = async (api: ReturnType<typeof serveRoutes>, step: number) => {
  await waitFor(() => expect(routerState.router.replace).toHaveBeenCalledWith(`/onboarding/${step}`));
  expect(api.callsTo('PUT', '/me/onboarding/step').at(-1)?.body).toEqual({ step });
};

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  routerState.reset();
  mockSignOut.mockClear();
  mockAvailability.mockReturnValue(queryResult());
});

// ---------------------------------------------------------------------------------------------
describe('onboarding routing', () => {
  it('shows a loader, then a retryable error', async () => {
    routerState.params = { step: '1' };
    const api = serveRoutes(fetchMock, [
      { path: '/me/onboarding', status: 503, body: { message: 'down' }, times: 1 },
      ...base(makeOnboardingState()),
    ]);
    await renderWithClient(<OnboardingStepScreen />);
    await waitFor(() => expect(screen.getByText("Couldn't load your progress")).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByText('Set up your profile')).toBeOnTheScreen());
    expect(api.callsTo('GET', '/me/onboarding')).toHaveLength(2);
  });

  it('shows the loader while loading', async () => {
    routerState.params = { step: '1' };
    serveRoutes(fetchMock, [{ path: '/me', hang: true }, { path: '/me/onboarding', hang: true }]);
    await renderWithClient(<OnboardingStepScreen />);
    expect(screen.getByLabelText('Loading your progress')).toBeOnTheScreen();
  });

  it.each([
    ['a later step', '6', 3, '/onboarding/3'],
    ['a bad number', 'abc', 4, '/onboarding/4'],
    ['step 0', '0', 2, '/onboarding/2'],
    ['past the end', '9', 8, '/onboarding/8'],
  ])('redirects %s to the saved step', async (_label, requested, saved, href) => {
    await open(requested, makeOnboardingState({ step: saved }));
    expect(screen.getByTestId('redirect')).toHaveTextContent(href);
  });

  it('lets you revisit an earlier step', async () => {
    await open(2, makeOnboardingState({ step: 5 }), [{ path: '/genres', body: GENRES }]);
    expect(screen.queryByTestId('redirect')).toBeNull();
    expect(screen.getByTestId('onboarding-step')).toHaveTextContent('Step 2 of 8');
  });

  it('treats a server step of 0 as step 1', async () => {
    await open(1, makeOnboardingState({ step: 0 }));
    expect(screen.getByText('Set up your profile')).toBeOnTheScreen();
  });

  it('sends finished users to the app, except on the reveal step', async () => {
    const done = makeProfile({ onboardingCompletedAt: '2026-10-16T00:00:00Z' });
    await open(3, makeOnboardingState({ step: 8, completed: true }), [], done);
    expect(screen.getByTestId('redirect')).toHaveTextContent('/');
  });

  describe('/onboarding (index)', () => {
    it('resumes at the saved step', async () => {
      serveRoutes(fetchMock, [{ path: '/me/onboarding', body: makeOnboardingState({ step: 4 }) }]);
      await renderWithClient(<OnboardingIndex />);
      await waitFor(() => expect(screen.getByTestId('redirect')).toHaveTextContent('/onboarding/4'));
    });

    it('starts a fresh user at step 1', async () => {
      serveRoutes(fetchMock, [{ path: '/me/onboarding', body: makeOnboardingState({ step: 0 }) }]);
      await renderWithClient(<OnboardingIndex />);
      await waitFor(() => expect(screen.getByTestId('redirect')).toHaveTextContent('/onboarding/1'));
    });

    it('sends completed users to the app', async () => {
      serveRoutes(fetchMock, [{ path: '/me/onboarding', body: makeOnboardingState({ step: 8, completed: true }) }]);
      await renderWithClient(<OnboardingIndex />);
      await waitFor(() => expect(screen.getByTestId('redirect')).toHaveTextContent('/'));
    });

    it('shows a retryable error', async () => {
      serveRoutes(fetchMock, [{ path: '/me/onboarding', status: 500, body: { message: 'x' } }]);
      await renderWithClient(<OnboardingIndex />);
      await waitFor(() => expect(screen.getByText("Couldn't load your progress")).toBeOnTheScreen());
    });
  });
});

// ---------------------------------------------------------------------------------------------
describe('step 1: You', () => {
  it('shows the initials of the name being typed on the avatar (bug 4: it showed "?")', async () => {
    await open(1, makeOnboardingState(), [], { ...profile, displayName: null, username: null, profileImageUrl: null });
    expect(screen.getByTestId('avatar-initials')).toHaveTextContent('?');
    await fireEvent.changeText(screen.getByLabelText('Display name'), 'Grace Hopper');
    expect(screen.getByTestId('avatar-initials')).toHaveTextContent('GH');
    await fireEvent.changeText(screen.getByLabelText('Display name'), 'Grace');
    expect(screen.getByTestId('avatar-initials')).toHaveTextContent('G');
    await fireEvent.changeText(screen.getByLabelText('Display name'), '');
    await fireEvent.changeText(screen.getByLabelText('Username'), 'turing');
    expect(screen.getByTestId('avatar-initials')).toHaveTextContent('T');
  });

  it('shows progress, prefills the profile and has no Back', async () => {
    await open(1, makeOnboardingState());
    expect(screen.getByTestId('onboarding-step')).toHaveTextContent('Step 1 of 8');
    expect(screen.getByTestId('onboarding-progress').props.accessibilityValue).toMatchObject({ now: 1, max: 8 });
    expect(screen.getByLabelText('Display name')).toHaveDisplayValue('Ada Lovelace');
    expect(screen.getByLabelText('Username')).toHaveDisplayValue('ada');
    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
    expect(continueButton()).toBeEnabled();
  });

  it('skips the PATCH when nothing changed and records step 2 before moving on', async () => {
    const { api } = await open(1, makeOnboardingState());
    await fireEvent.press(continueButton());
    await expectAdvancedTo(api, 2);
    expect(api.callsTo('PATCH', '/me')).toHaveLength(0);
  });

  it('PATCHes changed fields first', async () => {
    mockAvailability.mockReturnValue(queryResult({ data: { username: 'ada_l', valid: true, available: true } }));
    const { api } = await open(1, makeOnboardingState(), [
      { method: 'PATCH', path: '/me', body: { ...profile, username: 'ada_l', displayName: 'Ada' } },
    ]);
    await fireEvent.changeText(screen.getByLabelText('Display name'), 'Ada');
    await fireEvent.changeText(screen.getByLabelText('Username'), 'Ada_L');
    await waitFor(() => expect(screen.getByTestId('username-status')).toHaveTextContent(/available/i));
    await fireEvent.press(continueButton());
    await expectAdvancedTo(api, 2);
    expect(api.callsTo('PATCH', '/me')[0]?.body).toEqual({ username: 'ada_l', displayName: 'Ada' });
  });

  it('blocks Continue while the username is taken', async () => {
    mockAvailability.mockReturnValue(queryResult({ data: { username: 'grace', valid: true, available: false } }));
    const { api } = await open(1, makeOnboardingState());
    await fireEvent.changeText(screen.getByLabelText('Username'), 'grace');
    await waitFor(() => expect(screen.getByTestId('username-status')).toHaveTextContent('Already taken'));
    expect(continueButton()).toBeDisabled();
    expect(api.callsTo('PUT', '/me/onboarding/step')).toHaveLength(0);
  });

  it('shows PATCH field errors inline and stays put', async () => {
    mockAvailability.mockReturnValue(queryResult({ data: { username: 'bob', valid: true, available: true } }));
    const { api } = await open(1, makeOnboardingState(), [
      { method: 'PATCH', path: '/me', status: 400, body: { message: 'Invalid', fieldErrors: { username: 'Username is reserved.' } } },
    ]);
    await fireEvent.changeText(screen.getByLabelText('Username'), 'bob');
    await waitFor(() => expect(continueButton()).toBeEnabled());
    await fireEvent.press(continueButton());
    await waitFor(() => expect(screen.getByText('Username is reserved.')).toBeOnTheScreen());
    expect(api.callsTo('PUT', '/me/onboarding/step')).toHaveLength(0);
    expect(routerState.router.replace).not.toHaveBeenCalled();
  });

  it('shows a failed step save and does not navigate', async () => {
    await open(1, makeOnboardingState(), [
      { method: 'PUT', path: '/me/onboarding/step', status: 400, body: { message: 'Step must be between 0 and 8' } },
    ]);
    await fireEvent.press(continueButton());
    await waitFor(() => expect(screen.getByTestId('onboarding-error')).toHaveTextContent('Step must be between 0 and 8'));
    expect(routerState.router.replace).not.toHaveBeenCalled();
  });

  it('offers the photo picker and sign out', async () => {
    (pickSquarePhoto as jest.Mock).mockResolvedValue({ cancelled: true });
    await open(1, makeOnboardingState());
    await fireEvent.press(screen.getByRole('button', { name: 'Change photo' }));
    await waitFor(() => expect(pickSquarePhoto).toHaveBeenCalledWith('library'));
    await fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));
    expect(mockSignOut).toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------------------------
describe('step 2: Genres', () => {
  const genreRoute: Route = { path: '/genres', body: GENRES };
  const slide = (name: string, v: number) => slideTo(name, v);

  it('starts every slider at 5 with Continue locked and a live hint', async () => {
    await open(2, makeOnboardingState({ step: 2 }), [genreRoute]);
    await waitFor(() => expect(screen.getByTestId('genre-row-28')).toBeOnTheScreen());
    for (const g of GENRES) expect(screen.getByLabelText(g.name).props.accessibilityValue.now).toBe(5);
    expect(continueButton()).toBeDisabled();
    expect(screen.getByTestId('onboarding-hint')).toHaveTextContent('Move 5 more sliders off 5 and put 3 more genres at 8 or higher.');

    await slide('Action', 9);
    await slide('Comedy', 8);
    expect(screen.getByTestId('onboarding-hint')).toHaveTextContent('Move 3 more sliders off 5 and put 1 more genre at 8 or higher.');
    await slide('Drama', 10);
    await slide('Horror', 1);
    expect(continueButton()).toBeDisabled();
    await slide('Romance', 4);
    expect(continueButton()).toBeEnabled();
    expect(screen.getByTestId('onboarding-hint')).toHaveTextContent(/plenty/);
    // Moving one back to 5 locks it again.
    await slide('Romance', 5);
    expect(continueButton()).toBeDisabled();
  });

  it('saves touched genres, records step 3, then moves on', async () => {
    const { api } = await open(2, makeOnboardingState({ step: 2 }), [
      genreRoute,
      { method: 'PUT', path: '/me/preferences/genres', body: (b: unknown) => b },
    ]);
    await waitFor(() => expect(screen.getByTestId('genre-row-28')).toBeOnTheScreen());
    await slide('Action', 9);
    await slide('Comedy', 8);
    await slide('Drama', 10);
    await slide('Horror', 1);
    await slide('Romance', 3);
    await fireEvent.press(continueButton());
    await expectAdvancedTo(api, 3);
    expect(api.callsTo('PUT', '/me/preferences/genres')[0]?.body).toEqual([
      { genreId: 28, score: 9 },
      { genreId: 35, score: 8 },
      { genreId: 18, score: 10 },
      { genreId: 27, score: 1 },
      { genreId: 10749, score: 3 },
    ]);
    // The genres save goes first.
    const order = api.calls().filter((c) => c.method === 'PUT').map((c) => c.path);
    expect(order).toEqual(['/me/preferences/genres', '/me/onboarding/step']);
  });

  it('resumes with saved scores (and keeps them in the save)', async () => {
    const saved = [
      { genreId: 28, score: 9 },
      { genreId: 35, score: 8 },
      { genreId: 18, score: 8 },
      { genreId: 27, score: 2 },
      { genreId: 878, score: 5 },
    ];
    const { api } = await open(2, makeOnboardingState({ step: 3, genrePrefs: saved }), [
      genreRoute,
      { method: 'PUT', path: '/me/preferences/genres', body: (b: unknown) => b },
    ]);
    await waitFor(() => expect(screen.getByLabelText('Action').props.accessibilityValue.now).toBe(9));
    await slide('Romance', 7);
    await fireEvent.press(continueButton());
    await expectAdvancedTo(api, 3);
    expect(api.callsTo('PUT', '/me/preferences/genres')[0]?.body).toEqual([...saved, { genreId: 10749, score: 7 }]);
  });

  it('shows server validation errors inline and does not advance', async () => {
    const { api } = await open(2, makeOnboardingState({ step: 2 }), [
      genreRoute,
      { method: 'PUT', path: '/me/preferences/genres', status: 400, body: { message: 'Duplicate genre 28' } },
    ]);
    await waitFor(() => expect(screen.getByTestId('genre-row-28')).toBeOnTheScreen());
    for (const [name, v] of [['Action', 9], ['Comedy', 9], ['Drama', 9], ['Horror', 1], ['Romance', 1]] as const) {
      await slide(name, v);
    }
    await fireEvent.press(continueButton());
    await waitFor(() => expect(screen.getByTestId('onboarding-error')).toHaveTextContent('Duplicate genre 28'));
    expect(api.callsTo('PUT', '/me/onboarding/step')).toHaveLength(0);
  });

  it('goes Back to step 1, and retries a failed genre load', async () => {
    const api = (await open(2, makeOnboardingState({ step: 2 }), [
      { path: '/genres', status: 500, body: { message: 'x' }, times: 1 },
      genreRoute,
    ])).api;
    await waitFor(() => expect(screen.getByText("Couldn't load genres")).toBeOnTheScreen());
    expect(continueButton()).toBeDisabled();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByTestId('genre-row-28')).toBeOnTheScreen());
    expect(api.callsTo('GET', '/genres')).toHaveLength(2);
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/onboarding/1');
  });
});

// ---------------------------------------------------------------------------------------------
describe('step 3: Favourites', () => {
  const popular = [1, 2, 3, 4, 5].map((id) => makeMovie({ id, title: `Pop ${id}`, releaseYear: 2000 + id }));
  const routes: Route[] = [
    { path: '/movies/popular', body: { items: popular, nextCursor: null } },
    { path: '/movies/search?q=heat', body: { items: [makeMovie({ id: 100, title: 'Heat', releaseYear: 1995 })], nextCursor: null } },
    { method: 'PUT', path: '/me/preferences/favorites', body: [] },
  ];
  const pick = (title: string) => fireEvent.press(screen.getByRole('checkbox', { name: new RegExp(`^${title}`) }));
  const ranked = () => [1, 2, 3, 4, 5].flatMap((i) => {
    const row = screen.queryByTestId(`favorite-${i}`);
    return row ? [row] : [];
  });

  it('picks 3–10 from popular or search, ranks them, then saves ranks', async () => {
    const { api } = await open(3, makeOnboardingState({ step: 3 }), routes);
    await waitFor(() => expect(screen.getByTestId('favorites-results')).toBeOnTheScreen());
    expect(screen.getByTestId('favorites-count')).toHaveTextContent('0 of 10 picked');
    expect(continueButton()).toBeDisabled();
    expect(screen.getByTestId('onboarding-hint')).toHaveTextContent('Pick 3 more to continue.');

    await pick('Pop 1');
    await pick('Pop 2');
    expect(screen.getByRole('checkbox', { name: /^Pop 1/ })).toBeChecked();
    expect(continueButton()).toBeDisabled();

    await fireEvent.changeText(screen.getByLabelText('Search favourite movies'), 'heat');
    await waitFor(() => expect(screen.getByRole('checkbox', { name: 'Heat (1995)' })).toBeOnTheScreen());
    await pick('Heat');
    expect(screen.getByTestId('favorites-count')).toHaveTextContent('3 of 10 picked');
    expect(continueButton()).toBeEnabled();

    // Reorder: Heat to the top.
    await fireEvent.press(screen.getByRole('button', { name: 'Move Heat up' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Move Heat up' }));
    expect(ranked()).toHaveLength(3);
    expect(screen.getByTestId('favorite-1')).toHaveTextContent(/Heat/);
    expect(screen.getByRole('button', { name: 'Move Heat up' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('button', { name: 'Move Pop 2 down' }));
    expect(screen.getByRole('button', { name: 'Move Pop 2 down' })).toBeDisabled();

    await fireEvent.press(continueButton());
    await expectAdvancedTo(api, 4);
    expect(api.callsTo('PUT', '/me/preferences/favorites')[0]?.body).toEqual([
      { movieId: 100, rank: 1 },
      { movieId: 1, rank: 2 },
      { movieId: 2, rank: 3 },
    ]);
  });

  it('unpicks from the grid or the list, and stops at 10', async () => {
    const saved = Array.from({ length: 10 }, (_, i) => ({ rank: 10 - i, movie: makeMovie({ id: 50 + i, title: `Fav ${50 + i}` }) }));
    await open(3, makeOnboardingState({ step: 3, favorites: saved }), routes);
    await waitFor(() => expect(screen.getByTestId('favorites-results')).toBeOnTheScreen());
    // Sorted by saved rank.
    expect(screen.getByTestId('favorite-1')).toHaveTextContent(/Fav 59/);
    expect(screen.getByTestId('onboarding-hint')).toHaveTextContent(/That’s the maximum of 10/);
    await pick('Pop 1');
    expect(screen.getByTestId('favorites-count')).toHaveTextContent('10 of 10 picked');
    expect(screen.getByRole('checkbox', { name: /^Pop 1/ })).not.toBeChecked();

    await fireEvent.press(screen.getByRole('button', { name: 'Remove Fav 59' }));
    expect(screen.getByTestId('favorites-count')).toHaveTextContent('9 of 10 picked');
    await pick('Pop 1');
    await pick('Pop 1');
    expect(screen.getByTestId('favorites-count')).toHaveTextContent('9 of 10 picked');
  });

  it('shows a server error from the save', async () => {
    const { api } = await open(3, makeOnboardingState({ step: 3 }), [
      { method: 'PUT', path: '/me/preferences/favorites', status: 400, body: { message: 'Duplicate rank 1' } },
      ...routes,
    ]);
    await waitFor(() => expect(screen.getByTestId('favorites-results')).toBeOnTheScreen());
    await pick('Pop 1');
    await pick('Pop 2');
    await pick('Pop 3');
    await fireEvent.press(continueButton());
    await waitFor(() => expect(screen.getByTestId('onboarding-error')).toHaveTextContent('Duplicate rank 1'));
    expect(api.callsTo('PUT', '/me/onboarding/step')).toHaveLength(0);
  });

  it('says when a search finds nothing', async () => {
    await open(3, makeOnboardingState({ step: 3 }), [
      { path: '/movies/search?q=zzz', body: { items: [], nextCursor: null } },
      ...routes,
    ]);
    await fireEvent.changeText(screen.getByLabelText('Search favourite movies'), 'zzz');
    await waitFor(() => expect(screen.getByText('Nothing found for “zzz”.')).toBeOnTheScreen());
  });
});

// ---------------------------------------------------------------------------------------------
describe('step 4: Calibration', () => {
  const cards = Array.from({ length: 10 }, (_, i) => makeMovie({ id: 300 + i, title: `Cal ${i}` }));

  it('embeds the deck and advances from Done once 10 are rated', async () => {
    const { api } = await open(4, makeOnboardingState({ step: 4, calibrationRatedCount: 9 }), [
      { path: '/me/calibration/next?count=10', body: cards },
      { method: 'POST', path: '/me/ratings/batch', status: 204 },
    ]);
    await waitFor(() => expect(screen.getByTestId('calibration-card-300')).toBeOnTheScreen());
    expect(screen.getByTestId('calibration-progress')).toHaveTextContent('Rated 9 of 10');
    expect(screen.queryByRole('button', { name: 'Continue' })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Rate 5' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    await expectAdvancedTo(api, 5);
    expect(api.callsTo('POST', '/me/ratings/batch')[0]?.body).toEqual([{ movieId: 300, score: 5, unseen: false }]);
  });

  it('goes Back to step 3', async () => {
    await open(4, makeOnboardingState({ step: 4 }), [{ path: '/me/calibration/next?count=10', body: cards }]);
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/onboarding/3');
  });
});

// ---------------------------------------------------------------------------------------------
describe('step 5: Vibes', () => {
  const moodRoutes: Route[] = [
    { path: '/moods', body: MOODS },
    { method: 'PUT', path: '/me/preferences/moods', body: (b: unknown) => b },
  ];

  it('shows 4 vibes, then the rest under "More vibes"', async () => {
    await open(5, makeOnboardingState({ step: 5 }), moodRoutes);
    await waitFor(() => expect(screen.getByTestId('mood-row-mind-bending')).toBeOnTheScreen());
    expect(screen.getByTestId('mood-row-edge-of-seat')).toBeOnTheScreen();
    expect(screen.queryByTestId('mood-row-tearjerker')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'More vibes' }));
    expect(screen.getByTestId('mood-row-feel-good')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'More vibes' })).toBeNull();
  });

  it('is skippable: nothing touched → no mood save', async () => {
    const { api } = await open(5, makeOnboardingState({ step: 5 }), moodRoutes);
    await waitFor(() => expect(screen.getByTestId('mood-row-cozy')).toBeOnTheScreen());
    expect(continueButton()).toBeEnabled();
    await fireEvent.press(continueButton());
    await expectAdvancedTo(api, 6);
    expect(api.callsTo('PUT', '/me/preferences/moods')).toHaveLength(0);
  });

  it('saves touched (and previously saved) vibes', async () => {
    const { api } = await open(5, makeOnboardingState({ step: 5, moodPrefs: [{ moodTag: 'feel-good', score: 9 }] }), moodRoutes);
    await waitFor(() => expect(screen.getByTestId('mood-row-cozy')).toBeOnTheScreen());
    await slideTo('Cozy', 8);
    await fireEvent.press(continueButton());
    await expectAdvancedTo(api, 6);
    expect(api.callsTo('PUT', '/me/preferences/moods')[0]?.body).toEqual([
      { moodTag: 'cozy', score: 8 },
      { moodTag: 'feel-good', score: 9 },
    ]);
  });

  it('shows a server error from the save', async () => {
    const { api } = await open(5, makeOnboardingState({ step: 5 }), [
      { method: 'PUT', path: '/me/preferences/moods', status: 400, body: { message: 'Unknown mood tag' } },
      ...moodRoutes,
    ]);
    await waitFor(() => expect(screen.getByTestId('mood-row-cozy')).toBeOnTheScreen());
    await slideTo('Cozy', 2);
    await fireEvent.press(continueButton());
    await waitFor(() => expect(screen.getByTestId('onboarding-error')).toHaveTextContent('Unknown mood tag'));
    expect(api.callsTo('PUT', '/me/onboarding/step')).toHaveLength(0);
  });
});

// ---------------------------------------------------------------------------------------------
describe('step 6: Comfort', () => {
  it('reuses the comfort pickers, prefilled, and saves via PUT /me/settings/comfort', async () => {
    const comfort = makeComfort({ maxCertification: 'R', contentLimits: { SEXUAL: 'NONE' } });
    const { api, client } = await open(6, makeOnboardingState({ step: 6, comfort }), [
      { method: 'PUT', path: '/me/settings/comfort', body: (b: unknown) => b },
    ]);
    expect(screen.getByRole('radio', { name: 'R' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Sex & nudity: None' })).toBeChecked();
    await fireEvent.press(screen.getByRole('radio', { name: 'PG-13' }));
    // Below R the "Sex & nudity" row is hidden and SEXUAL is not sent.
    expect(screen.queryByText('Sex & nudity')).toBeNull();
    await fireEvent.press(screen.getByRole('radio', { name: 'Violence & gore: Mild' }));
    await fireEvent(screen.getByLabelText('Allow movies with unknown ratings'), 'valueChange', true);
    await fireEvent.press(continueButton());
    await expectAdvancedTo(api, 7);
    const sent = { maxCertification: 'PG_13', contentLimits: { VIOLENCE: 'MILD' }, allowUnrated: true };
    expect(api.callsTo('PUT', '/me/settings/comfort')[0]?.body).toEqual(sent);
    expect(client.getQueryData(queryKeys.comfortSettings)).toEqual(sent);
  });

  it.each([['G'], ['PG'], ['PG-13']])('hides "Sex & nudity" at %s and shows it again at R', async (label) => {
    await open(6, makeOnboardingState({ step: 6, comfort: makeComfort({ maxCertification: null, contentLimits: {} }) }), []);
    expect(screen.getByText('Sex & nudity')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('radio', { name: label }));
    expect(screen.queryByText('Sex & nudity')).toBeNull();
    await fireEvent.press(screen.getByRole('radio', { name: 'R' }));
    expect(screen.getByRole('radio', { name: 'Sex & nudity: Strong' })).toBeChecked();
    await fireEvent.press(screen.getByRole('radio', { name: 'NC-17' }));
    expect(screen.getByText('Sex & nudity')).toBeOnTheScreen();
  });

  it('shows a server error and stays', async () => {
    const { api } = await open(6, makeOnboardingState({ step: 6 }), [
      { method: 'PUT', path: '/me/settings/comfort', status: 400, body: { message: 'Invalid', fieldErrors: { maxCertification: 'Unknown rating.' } } },
    ]);
    await fireEvent.press(continueButton());
    await waitFor(() => expect(screen.getByTestId('onboarding-error')).toHaveTextContent('Unknown rating.'));
    expect(api.callsTo('PUT', '/me/onboarding/step')).toHaveLength(0);
  });
});

// ---------------------------------------------------------------------------------------------
describe('step 7: Find friends', () => {
  it('shows the invite card, searches people, and continues', async () => {
    const { api } = await open(7, makeOnboardingState({ step: 7 }), [
      { path: '/users/search?q=gr', body: { items: [makeUser()], nextCursor: null } },
    ]);
    expect(screen.getByRole('button', { name: 'Share invite link' })).toBeOnTheScreen();
    await fireEvent.changeText(screen.getByLabelText('Search people'), 'gr');
    await waitFor(() => expect(screen.getByText('Grace Hopper')).toBeOnTheScreen());
    expect(screen.queryByRole('button', { name: 'Share invite link' })).toBeNull();
    await fireEvent.press(continueButton());
    await expectAdvancedTo(api, 8);
  });

  it('shares an invite link', async () => {
    const { api } = await open(7, makeOnboardingState({ step: 7 }), [
      { method: 'POST', path: '/invites', body: { token: 't', url: 'https://filmfuse.app/invite/t', expiresAt: '2026-11-01T00:00:00Z' } },
    ]);
    await fireEvent.press(screen.getByRole('button', { name: 'Share invite link' }));
    await waitFor(() => expect(screen.getByTestId('invite-copied')).toBeOnTheScreen());
    expect(api.callsTo('POST', '/invites')).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------------------------
describe('step 8: Taste reveal', () => {
  const done = makeProfile({ ...profile, onboardingCompleted: true, onboardingCompletedAt: '2026-10-16T00:00:00Z' });

  it('completes onboarding, shows the persona while the first build runs, then lets you in', async () => {
    const { api, client } = await open(8, makeOnboardingState({ step: 8 }), [
      { method: 'POST', path: '/me/onboarding/complete', body: done },
      { path: '/me/taste-profile', body: makeTasteProfile({ status: 'REBUILDING' }), times: 1 },
      { path: '/me/taste-profile', body: makeTasteProfile({ status: 'FRESH' }) },
    ]);
    await waitFor(() => expect(screen.getByTestId('persona')).toHaveTextContent('Cozy Sci-Fi Romantic'));
    expect(api.callsTo('POST', '/me/onboarding/complete')).toHaveLength(1);
    expect(client.getQueryData<ProfileResponseDTO>(queryKeys.me)?.onboardingCompletedAt).toBe(done.onboardingCompletedAt);
    expect(screen.getByTestId('reveal-building')).toBeOnTheScreen();
    expect(screen.getByTestId('top-genres')).toHaveTextContent(/Science Fiction/);
    // A finished onboarding can't go back.
    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
    await fireEvent.press(continueButton('Start exploring'));
    expect(routerState.router.replace).toHaveBeenCalledWith('/');
  });

  it('hides the "still tuning" line once the profile is FRESH', async () => {
    await open(8, makeOnboardingState({ step: 8 }), [
      { method: 'POST', path: '/me/onboarding/complete', body: done },
      { path: '/me/taste-profile', body: makeTasteProfile({ status: 'FRESH' }) },
    ]);
    await waitFor(() => expect(screen.getByTestId('persona-card')).toBeOnTheScreen());
    expect(screen.queryByTestId('reveal-building')).toBeNull();
  });

  it('shows the minimums error from the server, with Back and Try again', async () => {
    const { api } = await open(8, makeOnboardingState({ step: 8 }), [
      { method: 'POST', path: '/me/onboarding/complete', status: 400, body: { message: 'Pick at least 3 favourites first.' }, times: 1 },
      { method: 'POST', path: '/me/onboarding/complete', body: done },
      { path: '/me/taste-profile', body: makeTasteProfile({ status: 'FRESH' }) },
    ]);
    await waitFor(() => expect(screen.getByTestId('reveal-error')).toHaveTextContent(/Pick at least 3 favourites first\./));
    expect(continueButton('Start exploring')).toBeDisabled();
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/onboarding/7');
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByTestId('persona-card')).toBeOnTheScreen());
    expect(api.callsTo('POST', '/me/onboarding/complete')).toHaveLength(2);
    expect(continueButton('Start exploring')).toBeEnabled();
  });

  it('shows a spinner while completing', async () => {
    await open(8, makeOnboardingState({ step: 8 }), [{ method: 'POST', path: '/me/onboarding/complete', hang: true }]);
    expect(screen.getByTestId('reveal-completing')).toBeOnTheScreen();
    expect(continueButton('Start exploring')).toBeDisabled();
  });
});
