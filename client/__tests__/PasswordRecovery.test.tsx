jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
const mockAuth = {
  session: { access_token: 't' } as unknown,
  passwordRecovery: false,
  updatePassword: jest.fn(),
  clearPasswordRecovery: jest.fn(),
  signOut: jest.fn(),
};
jest.mock('../auth/useAuth', () => ({ useAuth: () => mockAuth }));
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(), exchangeCodeForSession: jest.fn() } },
}));

import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import AuthCallbackScreen from '../app/auth/callback';
import ResetPasswordScreen from '../app/auth/reset-password';
import { RESET_PASSWORD_ROUTE } from '../auth/authRedirect';
import { RecoveryRedirect } from '../components/RecoveryRedirect';
import { supabase } from '../lib/supabase';
import { routerState } from '../test-utils/router';

beforeEach(() => {
  routerState.reset();
  mockAuth.session = { access_token: 't' };
  mockAuth.passwordRecovery = false;
  mockAuth.updatePassword.mockReset().mockResolvedValue({ data: {}, error: null });
  mockAuth.clearPasswordRecovery.mockReset();
  mockAuth.signOut.mockReset();
  (supabase.auth.getSession as jest.Mock).mockResolvedValue({ data: { session: null } });
  (supabase.auth.exchangeCodeForSession as jest.Mock).mockResolvedValue({ data: {}, error: null });
});

describe('recovery routing', () => {
  it('RecoveryRedirect sends a PASSWORD_RECOVERY session to the reset screen', async () => {
    mockAuth.passwordRecovery = true;
    routerState.pathname = '/discover';
    await render(<RecoveryRedirect />);
    expect(routerState.router.replace).toHaveBeenCalledWith(RESET_PASSWORD_ROUTE);
  });

  it('RecoveryRedirect does nothing otherwise, already there, or before navigation is ready', async () => {
    await render(<RecoveryRedirect />);
    mockAuth.passwordRecovery = true;
    routerState.pathname = RESET_PASSWORD_ROUTE;
    await screen.rerender(<RecoveryRedirect />);
    routerState.pathname = '/';
    routerState.navReady = false;
    await screen.rerender(<RecoveryRedirect />);
    expect(routerState.router.replace).not.toHaveBeenCalled();
  });

  it('the callback routes type=recovery links to the reset screen after the exchange', async () => {
    routerState.params = { code: 'c', type: 'recovery' };
    await render(<AuthCallbackScreen />);
    await waitFor(() => expect(routerState.router.replace).toHaveBeenCalledWith(RESET_PASSWORD_ROUTE));
    expect(supabase.auth.exchangeCodeForSession).toHaveBeenCalledWith('c');
  });
});

describe('ResetPasswordScreen', () => {
  const fill = async (pw: string, confirm: string) => {
    await fireEvent.changeText(screen.getByLabelText('New password'), pw);
    await fireEvent.changeText(screen.getByLabelText('Confirm new password'), confirm);
    await fireEvent.press(screen.getByRole('button', { name: 'Update password' }));
  };

  it('validates length and confirmation before calling Supabase', async () => {
    await render(<ResetPasswordScreen />);
    await fill('short', 'short');
    expect(screen.getByText('Use at least 8 characters')).toBeOnTheScreen();
    await fill('longenough', 'different');
    expect(screen.getByText("Passwords don't match")).toBeOnTheScreen();
    expect(mockAuth.updatePassword).not.toHaveBeenCalled();
  });

  it('updates the password, shows success, then continues into the app', async () => {
    await render(<ResetPasswordScreen />);
    await fill('brand-new-pw', 'brand-new-pw');
    expect(mockAuth.updatePassword).toHaveBeenCalledWith('brand-new-pw');
    expect(await screen.findByText('Password updated')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Continue to FilmFuse' }));
    expect(mockAuth.clearPasswordRecovery).toHaveBeenCalled();
    expect(routerState.router.replace).toHaveBeenCalledWith('/');
  });

  it('shows Supabase errors inline', async () => {
    mockAuth.updatePassword.mockResolvedValue({
      data: {},
      error: { message: 'New password should be different from the old password.' },
    });
    await render(<ResetPasswordScreen />);
    await fill('same-old-pw', 'same-old-pw');
    expect(await screen.findByTestId('reset-error')).toHaveTextContent(/different from the old password/);
    expect(screen.queryByText('Password updated')).toBeNull();
  });

  it('shows a connection error when the call throws', async () => {
    mockAuth.updatePassword.mockRejectedValue(new Error('offline'));
    await render(<ResetPasswordScreen />);
    await fill('brand-new-pw', 'brand-new-pw');
    expect(await screen.findByTestId('reset-error')).toHaveTextContent(/Couldn't reach/);
  });

  it('can cancel and sign out', async () => {
    await render(<ResetPasswordScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel and sign out' }));
    expect(mockAuth.clearPasswordRecovery).toHaveBeenCalled();
    expect(mockAuth.signOut).toHaveBeenCalled();
  });

  it('explains an expired link when there is no session', async () => {
    mockAuth.session = null;
    await render(<ResetPasswordScreen />);
    expect(screen.getByText('This reset link has expired')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Back to sign in' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/sign-in');
  });
});
