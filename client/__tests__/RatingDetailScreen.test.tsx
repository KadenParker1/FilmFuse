jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));
let mockSession: unknown = { access_token: 't' };
jest.mock('../auth/useAuth', () => ({ useAuth: () => ({ session: mockSession }) }));
jest.mock('../lib/pendingLink', () => ({ setPendingLink: jest.fn(() => Promise.resolve()) }));
jest.mock('../components/rating/QuickRateProvider', () => ({
  useQuickRate: () => ({ openQuickRate: jest.fn(), openRatePicker: jest.fn(), showTasteUpdating: jest.fn() }),
}));

import { fireEvent, screen, waitFor, within } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import RatingDetailRoute from '../app/ratings/[id]';
import { validateComment } from '../components/comments/CommentsSection';
import { queryKeys } from '../lib/queryKeys';
import { setPendingLink } from '../lib/pendingLink';
import { serveRoutes, type Route } from '../test-utils/fetchRoutes';
import { makeComment, makeFeedItem, makeProfile, makeUser } from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderWithClient } from '../test-utils/render';
import { routerState } from '../test-utils/router';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  routerState.reset();
  routerState.params = { id: 'f1' };
  mockSession = { access_token: 't' };
  (setPendingLink as jest.Mock).mockClear();
  global.fetch = fetchMock as unknown as typeof fetch;
});

const page = (items: unknown[], nextCursor: string | null = null) => ({ items, nextCursor });
const grace = makeUser({ id: 'u2', displayName: 'Grace Hopper', friendship: 'FRIENDS' });
const alan = makeUser({ id: 'u3', displayName: 'Alan Turing', friendship: 'FRIENDS' });
const me = makeUser({ id: 'u1', displayName: 'Ada Lovelace', username: 'ada' });
const rating = makeFeedItem({ id: 'f1', author: grace, commentCount: 2, body: 'So tense.' });
const fromAlan = makeComment({ id: 'c1', author: alan, body: 'Agreed!', mine: false, canDelete: false });
const fromMe = makeComment({ id: 'c2', author: me, body: 'Same here', mine: true, canDelete: true });

const routes = (over: Route[] = []): Route[] => [
  ...over,
  { path: '/ratings/f1', body: rating },
  { path: '/ratings/f1/comments', body: page([fromAlan, fromMe]) },
];

const renderScreen = () => {
  const client = createTestQueryClient();
  client.setQueryData(queryKeys.me, makeProfile({ id: 'u1', displayName: 'Ada Lovelace', username: 'ada' }));
  return renderWithClient(<RatingDetailRoute />, client);
};
const count = () => screen.getByTestId('feed-comment-count-f1');

describe('RatingDetailScreen — states', () => {
  it('shows a skeleton while the rating loads', async () => {
    serveRoutes(fetchMock, [{ path: '/ratings/f1', hang: true }]);
    await renderScreen();
    expect(screen.getByTestId('rating-loading')).toBeOnTheScreen();
    expect(screen.getByRole('header', { name: 'Rating' })).toBeOnTheScreen();
  });

  it('a 404 (deleted, private, or blocked) shows a friendly not-available state', async () => {
    serveRoutes(fetchMock, [{ path: '/ratings/f1', status: 404, body: { message: 'Not found' } }]);
    await renderScreen();
    expect(await screen.findByText("This rating isn't available")).toBeOnTheScreen();
    expect(screen.getByText("It may have been deleted, or it isn't shared with you.")).toBeOnTheScreen();
    expect(screen.queryByTestId('comments')).toBeNull();
    expect(screen.getByRole('button', { name: 'Back' })).toBeOnTheScreen();
  });

  it('no id at all is not found too, without fetching', async () => {
    routerState.params = {};
    await renderScreen();
    expect(screen.getByTestId('rating-not-found')).toBeOnTheScreen();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('other failures offer a retry', async () => {
    const api = serveRoutes(fetchMock, routes([{ path: '/ratings/f1', status: 500, body: { message: 'x' }, times: 1 }]));
    await renderScreen();
    expect(await screen.findByText("Couldn't load this rating")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByTestId('feed-card-f1')).toBeOnTheScreen();
    expect(api.callsTo('GET', '/ratings/f1')).toHaveLength(2);
  });

  it('signed out: remembers the link and sends you to sign in', async () => {
    mockSession = null;
    await renderScreen();
    await waitFor(() => expect(routerState.router.replace).toHaveBeenCalledWith('/sign-in'));
    expect(setPendingLink).toHaveBeenCalledWith('/ratings/f1');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('RatingDetailScreen — rating and comments', () => {
  it('shows the rating card (count not a link here) and the comments, oldest first', async () => {
    serveRoutes(fetchMock, routes());
    await renderScreen();
    expect(await screen.findByTestId('feed-card-f1')).toBeOnTheScreen();
    expect(screen.getByTestId('feed-note-f1')).toHaveTextContent('So tense.');
    expect(count()).toHaveTextContent('2');
    expect(screen.queryByRole('button', { name: '2 comments' })).toBeNull();
    const list = await screen.findByTestId('comments-list');
    const rows = within(list).getAllByTestId(/^comment-c/);
    expect(rows.map((r) => r.props.testID)).toEqual(['comment-c1', 'comment-c2']);
    expect(within(rows[0]!).getByText('Alan Turing')).toBeOnTheScreen();
    expect(within(rows[0]!).getByText('Agreed!')).toBeOnTheScreen();
    expect(within(rows[1]!).getByText('You')).toBeOnTheScreen();
  });

  it('a commenter opens their profile', async () => {
    serveRoutes(fetchMock, routes());
    await renderScreen();
    await fireEvent.press(await screen.findByRole('button', { name: "Alan Turing's profile" }));
    expect(routerState.router.push).toHaveBeenCalledWith('/user/u3');
  });

  it('empty comments invite the first one', async () => {
    serveRoutes(fetchMock, routes([{ path: '/ratings/f1/comments', body: page([]) }]));
    await renderScreen();
    expect(await screen.findByTestId('comments-empty')).toHaveTextContent('No comments yet. Start the conversation.');
  });

  it('comments that fail to load offer a retry', async () => {
    serveRoutes(
      fetchMock,
      routes([{ path: '/ratings/f1/comments', status: 500, body: { message: 'x' }, times: 1 }]),
    );
    await renderScreen();
    expect(await screen.findByText("Couldn't load comments")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByTestId('comments-list')).toBeOnTheScreen();
  });

  it('pages older → newer with "Show more comments"', async () => {
    const later = makeComment({ id: 'c5', author: alan, body: 'One more thing' });
    const api = serveRoutes(
      fetchMock,
      routes([
        { path: '/ratings/f1/comments', body: page([fromAlan], 'p2'), times: 1 },
        { path: '/ratings/f1/comments?cursor=p2', body: page([later]) },
      ]),
    );
    await renderScreen();
    await screen.findByTestId('comment-c1');
    expect(screen.queryByTestId('comment-c5')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Show more comments' }));
    expect(await screen.findByTestId('comment-c5')).toBeOnTheScreen();
    expect(api.callsTo('GET', '/ratings/f1/comments?cursor=p2')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Show more comments' })).toBeNull();
  });
});

describe('RatingDetailScreen — composer', () => {
  it('Post is disabled for an empty or blank draft; the counter tracks length; max 500', async () => {
    serveRoutes(fetchMock, routes());
    await renderScreen();
    const input = await screen.findByLabelText('Add a comment');
    expect(input.props.maxLength).toBe(500);
    const post = screen.getByRole('button', { name: 'Post comment' });
    expect(post).toBeDisabled();
    expect(screen.getByTestId('comment-counter')).toHaveTextContent('0/500');
    await fireEvent.changeText(input, '   ');
    expect(screen.getByRole('button', { name: 'Post comment' })).toBeDisabled();
    expect(screen.getByTestId('comment-counter')).toHaveTextContent('3/500');
    await fireEvent.changeText(input, 'Hi');
    expect(screen.getByRole('button', { name: 'Post comment' })).toBeEnabled();
  });

  it('posts: shows it at once (pending), bumps the count, then keeps the saved one', async () => {
    let resolvePost!: (v: Response) => void;
    const saved = makeComment({ id: 'c9', author: me, body: 'Loved it', mine: true, canDelete: true });
    serveRoutes(fetchMock, routes());
    // A hand-resolved POST so the pending state can be inspected.
    const served = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((url: string, init: RequestInit = {}) =>
      init.method === 'POST' ? new Promise<Response>((r) => (resolvePost = r)) : served(url, init),
    );
    await renderScreen();
    await screen.findByTestId('comments-list');
    await fireEvent.changeText(screen.getByLabelText('Add a comment'), '  Loved it  ');
    await fireEvent.press(screen.getByRole('button', { name: 'Post comment' }));

    expect(screen.getByLabelText('Add a comment').props.value).toBe('');
    expect(screen.getByText('Posting…')).toBeOnTheScreen();
    expect(screen.getByText('Loved it')).toBeOnTheScreen();
    expect(count()).toHaveTextContent('3');
    expect(JSON.parse((fetchMock.mock.calls.at(-1)![1] as RequestInit).body as string)).toEqual({ body: 'Loved it' });

    resolvePost(jsonResponse(201, saved));
    expect(await screen.findByTestId('comment-c9')).toBeOnTheScreen();
    expect(screen.queryByText('Posting…')).toBeNull();
    expect(count()).toHaveTextContent('3');
  });

  it('a refused post removes it, restores the count and gives the draft back', async () => {
    serveRoutes(
      fetchMock,
      routes([{ method: 'POST', path: '/ratings/f1/comments', status: 404, body: { message: 'Rating not found' } }]),
    );
    await renderScreen();
    await screen.findByTestId('comments-list');
    await fireEvent.changeText(screen.getByLabelText('Add a comment'), 'Hello?');
    await fireEvent.press(screen.getByRole('button', { name: 'Post comment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Rating not found');
    expect(screen.queryByText('Posting…')).toBeNull();
    expect(screen.queryByText('Hello?', { exact: true })).toBeNull();
    expect(screen.getByLabelText('Add a comment').props.value).toBe('Hello?');
    expect(count()).toHaveTextContent('2');
  });

  it('shows server field errors', async () => {
    serveRoutes(
      fetchMock,
      routes([
        {
          method: 'POST',
          path: '/ratings/f1/comments',
          status: 400,
          body: { message: 'Invalid', fieldErrors: { body: 'must be at most 500 characters' } },
        },
      ]),
    );
    await renderScreen();
    await screen.findByTestId('comments-list');
    await fireEvent.changeText(screen.getByLabelText('Add a comment'), 'x');
    await fireEvent.press(screen.getByRole('button', { name: 'Post comment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('must be at most 500 characters');
  });

  it('validateComment', () => {
    expect(validateComment('')).toBe('Write something first.');
    expect(validateComment('  \n ')).toBe('Write something first.');
    expect(validateComment('ok')).toBeNull();
    expect(validateComment(` ${'x'.repeat(500)} `)).toBeNull();
    expect(validateComment('x'.repeat(501))).toBe('Keep it under 500 characters.');
  });
});

describe('RatingDetailScreen — deleting', () => {
  it('only comments you may delete have a delete button', async () => {
    serveRoutes(fetchMock, routes());
    await renderScreen();
    await screen.findByTestId('comments-list');
    expect(screen.getByRole('button', { name: 'Delete your comment' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: "Delete Alan Turing's comment" })).toBeNull();
  });

  it("on your own rating you can delete others' comments too", async () => {
    serveRoutes(
      fetchMock,
      routes([
        { path: '/ratings/f1', body: { ...rating, author: me, mine: true } },
        { path: '/ratings/f1/comments', body: page([{ ...fromAlan, canDelete: true }]) },
      ]),
    );
    await renderScreen();
    expect(await screen.findByRole('button', { name: "Delete Alan Turing's comment" })).toBeOnTheScreen();
  });

  it('asks first; Cancel keeps it', async () => {
    serveRoutes(fetchMock, routes());
    await renderScreen();
    await fireEvent.press(await screen.findByRole('button', { name: 'Delete your comment' }));
    expect(screen.getByText('Delete this comment?')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByText('Delete this comment?')).toBeNull();
    expect(screen.getByTestId('comment-c2')).toBeOnTheScreen();
    expect(fetchMock.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === 'DELETE')).toBe(false);
  });

  it('confirming removes it at once, lowers the count and DELETEs it', async () => {
    const api = serveRoutes(fetchMock, routes([{ method: 'DELETE', path: '/comments/c2', status: 204 }]));
    await renderScreen();
    await fireEvent.press(await screen.findByRole('button', { name: 'Delete your comment' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Delete' }));
    expect(screen.queryByTestId('comment-c2')).toBeNull();
    expect(count()).toHaveTextContent('1');
    await waitFor(() => expect(api.callsTo('DELETE', '/comments/c2')).toHaveLength(1));
  });

  it('a refused delete puts it back and says so', async () => {
    serveRoutes(
      fetchMock,
      routes([{ method: 'DELETE', path: '/comments/c2', status: 403, body: { message: "You can't delete this" } }]),
    );
    await renderScreen();
    await fireEvent.press(await screen.findByRole('button', { name: 'Delete your comment' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Delete' }));
    expect(await screen.findByTestId('comment-delete-error')).toHaveTextContent("Couldn't delete the comment. You can't delete this");
    expect(screen.getByTestId('comment-c2')).toBeOnTheScreen();
    expect(count()).toHaveTextContent('2');
  });
});

describe('RatingDetailScreen — reporting', () => {
  it("others' comments (not yours) can be reported as a COMMENT", async () => {
    const api = serveRoutes(fetchMock, routes([{ method: 'POST', path: '/reports', status: 204 }]));
    await renderScreen();
    await screen.findByTestId('comments-list');
    expect(screen.queryByRole('button', { name: "Options for You's comment" })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: "Options for Alan Turing's comment" }));
    expect(screen.getByText("Alan Turing's comment")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Report comment' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Harassment' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Send report' }));
    expect(await screen.findByText('Thanks for letting us know')).toBeOnTheScreen();
    expect(api.callsTo('POST', '/reports')[0]!.body).toEqual({
      targetType: 'COMMENT',
      targetId: 'c1',
      reason: 'HARASSMENT',
      details: null,
    });
  });

  it('the rating itself can be reported from its card', async () => {
    const api = serveRoutes(fetchMock, routes([{ method: 'POST', path: '/reports', status: 204 }]));
    await renderScreen();
    await fireEvent.press(await screen.findByRole('button', { name: "Options for Grace Hopper's rating" }));
    await fireEvent.press(screen.getByRole('button', { name: 'Report rating' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Spam' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Send report' }));
    await screen.findByText('Thanks for letting us know');
    expect(api.callsTo('POST', '/reports')[0]!.body).toMatchObject({ targetType: 'RATING', targetId: 'f1' });
  });
});
