jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));
jest.mock('../components/rating/QuickRateProvider', () => ({
  useQuickRate: () => ({ openQuickRate: jest.fn(), openRatePicker: jest.fn(), showTasteUpdating: jest.fn() }),
}));

import { Alert } from 'react-native';
import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { FeedCard } from '../components/feed/FeedCard';
import { REASON_CHOICES, REPORT_THANKS, ReportForm, ReportSheet } from '../components/moderation/ReportSheet';
import { serveRoutes } from '../test-utils/fetchRoutes';
import { makeFeedItem, makeUser } from '../test-utils/fixtures';
import { renderWithClient } from '../test-utils/render';
import { routerState } from '../test-utils/router';
import type { ReportTargetType } from '../types/api';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  routerState.reset();
  global.fetch = fetchMock as unknown as typeof fetch;
});

const send = () => screen.getByRole('button', { name: 'Send report' });

describe('ReportForm — validation', () => {
  it('needs a reason before it can send', async () => {
    serveRoutes(fetchMock, [{ method: 'POST', path: '/reports', status: 204 }]);
    await renderWithClient(<ReportForm targetType="RATING" targetId="f1" onDone={jest.fn()} />);
    expect(screen.getByRole('header', { name: 'Report this rating' })).toBeOnTheScreen();
    expect(send()).toBeDisabled();
    expect(screen.getByTestId('report-reason-hint')).toBeOnTheScreen();
    REASON_CHOICES.forEach((c) => expect(screen.getByRole('radio', { name: c.label })).not.toBeChecked());
    await fireEvent.press(screen.getByRole('radio', { name: 'Harassment' }));
    expect(screen.getByRole('radio', { name: 'Harassment' })).toBeChecked();
    expect(send()).toBeEnabled();
    expect(screen.queryByTestId('report-reason-hint')).toBeNull();
  });

  it('caps details at 500 with a live counter', async () => {
    await renderWithClient(<ReportForm targetType="COMMENT" targetId="c1" onDone={jest.fn()} />);
    const details = screen.getByLabelText('Details (optional)');
    expect(details.props.maxLength).toBe(500);
    expect(screen.getByTestId('report-details-counter')).toHaveTextContent('0/500');
    await fireEvent.changeText(details, 'abc');
    expect(screen.getByTestId('report-details-counter')).toHaveTextContent('3/500');
  });

  it('refuses over-long details even if they get past the input', async () => {
    const api = serveRoutes(fetchMock, [{ method: 'POST', path: '/reports', status: 204 }]);
    await renderWithClient(<ReportForm targetType="USER" targetId="u2" onDone={jest.fn()} />);
    await fireEvent.press(screen.getByRole('radio', { name: 'Spam' }));
    await fireEvent.changeText(screen.getByLabelText('Details (optional)'), 'x'.repeat(501));
    expect(send()).toBeDisabled();
    await fireEvent.press(send());
    expect(api.callsTo('POST', '/reports')).toHaveLength(0);
  });

  it.each([
    ['RATING', 'rating'],
    ['COMMENT', 'comment'],
    ['USER', 'person'],
  ] as const)('names a %s target in the heading', async (type: ReportTargetType, noun) => {
    await renderWithClient(<ReportForm targetType={type} targetId="x" onDone={jest.fn()} />);
    expect(screen.getByRole('header', { name: `Report this ${noun}` })).toBeOnTheScreen();
  });
});

describe('ReportForm — sending', () => {
  it('POSTs the target, reason and trimmed details, then thanks you; Done closes', async () => {
    const api = serveRoutes(fetchMock, [{ method: 'POST', path: '/reports', status: 204 }]);
    const onDone = jest.fn();
    await renderWithClient(<ReportForm targetType="COMMENT" targetId="c1" onDone={onDone} />);
    await fireEvent.press(screen.getByRole('radio', { name: 'Unmarked spoilers' }));
    await fireEvent.changeText(screen.getByLabelText('Details (optional)'), '  ruined the ending  ');
    await fireEvent.press(send());
    expect(await screen.findByTestId('report-success')).toBeOnTheScreen();
    expect(screen.getByText(REPORT_THANKS)).toBeOnTheScreen();
    expect(api.callsTo('POST', '/reports')[0]!.body).toEqual({
      targetType: 'COMMENT',
      targetId: 'c1',
      reason: 'SPOILERS',
      details: 'ruined the ending',
    });
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('sends blank details as null', async () => {
    const api = serveRoutes(fetchMock, [{ method: 'POST', path: '/reports', status: 204 }]);
    await renderWithClient(<ReportForm targetType="USER" targetId="u2" onDone={jest.fn()} />);
    await fireEvent.press(screen.getByRole('radio', { name: 'Something else' }));
    await fireEvent.press(send());
    await screen.findByTestId('report-success');
    expect(api.callsTo('POST', '/reports')[0]!.body).toEqual({
      targetType: 'USER',
      targetId: 'u2',
      reason: 'OTHER',
      details: null,
    });
  });

  it('is busy while sending', async () => {
    serveRoutes(fetchMock, [{ method: 'POST', path: '/reports', hang: true }]);
    await renderWithClient(<ReportForm targetType="RATING" targetId="f1" onDone={jest.fn()} />);
    await fireEvent.press(screen.getByRole('radio', { name: 'Hate' }));
    await fireEvent.press(send());
    await waitFor(() => expect(send()).toBeBusy());
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
  });

  it.each([
    ['a server error', 500, { message: 'boom' }, /The server had a problem/],
    ['a gone target (404)', 404, { message: 'Not found' }, "This rating isn't available anymore."],
    ['a self-report (400)', 400, { message: "You can't report yourself" }, "You can't report yourself"],
    ['a field error', 400, { message: 'Invalid', fieldErrors: { details: 'Too long' } }, 'Too long'],
  ])('shows %s inline (never an alert dialog) and lets you retry', async (_label, status, body, text) => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const api = serveRoutes(fetchMock, [{ method: 'POST', path: '/reports', status, body, times: 1 }]);
    await renderWithClient(<ReportForm targetType="RATING" targetId="f1" onDone={jest.fn()} />);
    await fireEvent.press(screen.getByRole('radio', { name: 'Spam' }));
    await fireEvent.press(send());
    expect(await screen.findByRole('alert')).toHaveTextContent(text);
    expect(screen.queryByTestId('report-success')).toBeNull();
    expect(alertSpy).not.toHaveBeenCalled();
    alertSpy.mockRestore();
    api.use({ method: 'POST', path: '/reports', status: 204 });
    await fireEvent.press(send());
    expect(await screen.findByTestId('report-success')).toBeOnTheScreen();
  });

  it('changing the reason clears an old error', async () => {
    serveRoutes(fetchMock, [{ method: 'POST', path: '/reports', status: 500, body: { message: 'boom' } }]);
    await renderWithClient(<ReportForm targetType="RATING" targetId="f1" onDone={jest.fn()} />);
    await fireEvent.press(screen.getByRole('radio', { name: 'Spam' }));
    await fireEvent.press(send());
    await screen.findByRole('alert');
    await fireEvent.press(screen.getByRole('radio', { name: 'Hate' }));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('Cancel closes without sending', async () => {
    const onDone = jest.fn();
    await renderWithClient(<ReportForm targetType="RATING" targetId="f1" onDone={onDone} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(onDone).toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('ReportSheet', () => {
  it('without a menu opens straight on the form', async () => {
    await renderWithClient(<ReportSheet visible targetType="USER" targetId="u2" onClose={jest.fn()} />);
    expect(screen.getByTestId('report-form')).toBeOnTheScreen();
  });

  it('with a menu shows its title and a Report entry first; Cancel closes', async () => {
    const onClose = jest.fn();
    await renderWithClient(
      <ReportSheet visible menuTitle="Grace's comment" targetType="COMMENT" targetId="c1" onClose={onClose} />,
    );
    expect(screen.getByText("Grace's comment")).toBeOnTheScreen();
    expect(screen.queryByTestId('report-form')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('button', { name: 'Report comment' }));
    expect(screen.getByTestId('report-form')).toBeOnTheScreen();
  });
});

describe('Reporting from a FeedCard', () => {
  const grace = makeUser({ id: 'u2', displayName: 'Grace Hopper', friendship: 'FRIENDS' });

  it("someone else's rating: … → Report rating → reason → sent as a RATING report", async () => {
    const api = serveRoutes(fetchMock, [{ method: 'POST', path: '/reports', status: 204 }]);
    await renderWithClient(<FeedCard item={makeFeedItem({ id: 'f9', author: grace })} />);
    await fireEvent.press(screen.getByRole('button', { name: "Options for Grace Hopper's rating" }));
    expect(screen.getByText("Grace Hopper's rating")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Report rating' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Spam' }));
    await fireEvent.press(send());
    expect(await screen.findByText(REPORT_THANKS)).toBeOnTheScreen();
    expect(api.callsTo('POST', '/reports')[0]!.body).toEqual({
      targetType: 'RATING',
      targetId: 'f9',
      reason: 'SPAM',
      details: null,
    });
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByText(REPORT_THANKS)).toBeNull();
  });

  it('your own rating has no report option', async () => {
    await renderWithClient(<FeedCard item={makeFeedItem({ id: 'f9', mine: true })} />);
    expect(screen.queryByRole('button', { name: /^Options for/ })).toBeNull();
  });
});
