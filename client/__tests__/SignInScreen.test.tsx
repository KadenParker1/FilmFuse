let mockShowApple = true;
const mockAuth = {
  signInWithPassword: jest.fn(),
  signUp: jest.fn(),
  resetPassword: jest.fn(),
};
jest.mock('../auth/useAuth', () => ({ useAuth: () => mockAuth }));
jest.mock('../auth/oauth', () => ({
  get showAppleButton() {
    return mockShowApple;
  },
  signInWithProvider: jest.fn(),
}));

// The real button switches on Platform.OS; that's covered in AppleSignInButton.test.tsx.
jest.mock('../components/AppleSignInButton', () => {
  const { Pressable, Text } = jest.requireActual<typeof import('react-native')>('react-native');
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    AppleSignInButton: ({ onPress }: { onPress: () => void }) =>
      React.createElement(
        Pressable,
        { accessibilityRole: 'button', accessibilityLabel: 'Continue with Apple', onPress },
        React.createElement(Text, null, 'Continue with Apple'),
      ),
  };
});

import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import SignInScreen from '../app/(auth)/sign-in';
import { signInWithProvider } from '../auth/oauth';
import { validateAuthForm, validateNewPassword } from '../auth/validateAuthForm';

const mockProvider = signInWithProvider as jest.Mock;

beforeEach(() => {
  mockShowApple = true;
  Object.values(mockAuth).forEach((f) => f.mockReset());
  mockProvider.mockReset();
  mockAuth.signInWithPassword.mockResolvedValue({ data: { session: {} }, error: null });
  mockAuth.signUp.mockResolvedValue({ data: { session: null }, error: null });
  mockAuth.resetPassword.mockResolvedValue({ data: {}, error: null });
  mockProvider.mockResolvedValue({ ok: true });
});

const type = (label: string, text: string) => fireEvent.changeText(screen.getByLabelText(label), text);
const press = (name: string | RegExp) => fireEvent.press(screen.getByRole('button', { name }));

describe('validateAuthForm', () => {
  it('checks email in every mode', () => {
    expect(validateAuthForm('forgot', 'nope', '', '')).toEqual({ email: 'Enter a valid email address' });
    expect(validateAuthForm('forgot', 'a@b.co', '', '')).toEqual({});
  });
  it('requires a password to sign in', () => {
    expect(validateAuthForm('sign-in', 'a@b.co', '', '')).toEqual({ password: 'Enter your password' });
  });
  it('shares new-password rules with the reset screen', () => {
    expect(validateNewPassword('short', 'short')).toEqual({ password: 'Use at least 8 characters' });
    expect(validateNewPassword('longenough', 'nope')).toEqual({ confirm: "Passwords don't match" });
    expect(validateNewPassword('longenough', 'longenough')).toEqual({});
  });
  it('requires 8+ chars and a matching confirmation to sign up', () => {
    expect(validateAuthForm('sign-up', 'a@b.co', 'short', 'short')).toEqual({ password: 'Use at least 8 characters' });
    expect(validateAuthForm('sign-up', 'a@b.co', 'longenough', 'different')).toEqual({ confirm: "Passwords don't match" });
    expect(validateAuthForm('sign-up', 'a@b.co', 'longenough', 'longenough')).toEqual({});
  });
});

describe('SignInScreen', () => {
  it('signs in with email and password', async () => {
    await render(<SignInScreen />);
    await type('Email', ' ada@example.com ');
    await type('Password', 'secret');
    await press('Sign in');
    expect(mockAuth.signInWithPassword).toHaveBeenCalledWith('ada@example.com', 'secret');
  });

  it('shows validation errors without calling Supabase', async () => {
    await render(<SignInScreen />);
    await press('Sign in');
    expect(screen.getByText('Enter a valid email address')).toBeOnTheScreen();
    expect(screen.getByText('Enter your password')).toBeOnTheScreen();
    expect(mockAuth.signInWithPassword).not.toHaveBeenCalled();
  });

  it('shows a Supabase error inline', async () => {
    mockAuth.signInWithPassword.mockResolvedValue({ data: { session: null }, error: { message: 'Invalid login credentials' } });
    await render(<SignInScreen />);
    await type('Email', 'ada@example.com');
    await type('Password', 'wrong');
    await press('Sign in');
    expect(await screen.findByText('Invalid login credentials')).toBeOnTheScreen();
  });

  it('shows a connection error inline when the call throws', async () => {
    mockAuth.signInWithPassword.mockRejectedValue(new Error('offline'));
    await render(<SignInScreen />);
    await type('Email', 'ada@example.com');
    await type('Password', 'x');
    await press('Sign in');
    expect(await screen.findByText("Couldn't reach the sign-in service. Try again.")).toBeOnTheScreen();
  });

  it('sign up rejects mismatched passwords', async () => {
    await render(<SignInScreen />);
    await press(/Create an account/);
    expect(screen.getByRole('header', { name: 'Grab a seat' })).toBeOnTheScreen();
    await type('Email', 'ada@example.com');
    await type('Password', 'longenough');
    await type('Confirm password', 'different1');
    await press('Create account');
    expect(screen.getByText("Passwords don't match")).toBeOnTheScreen();
    expect(mockAuth.signUp).not.toHaveBeenCalled();
  });

  it('sign up asks to confirm email when no session comes back', async () => {
    await render(<SignInScreen />);
    await press(/Create an account/);
    await type('Email', 'ada@example.com');
    await type('Password', 'longenough');
    await type('Confirm password', 'longenough');
    await press('Create account');
    expect(mockAuth.signUp).toHaveBeenCalledWith('ada@example.com', 'longenough');
    expect(await screen.findByText(/Check your inbox/)).toBeOnTheScreen();
  });

  it('forgot password sends a reset email', async () => {
    await render(<SignInScreen />);
    await press('Forgot password?');
    expect(screen.getByRole('header', { name: 'Reset your password' })).toBeOnTheScreen();
    expect(screen.queryByLabelText('Password')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Continue with Google' })).toBeNull();
    await type('Email', 'ada@example.com');
    await press('Send reset link');
    expect(mockAuth.resetPassword).toHaveBeenCalledWith('ada@example.com');
    expect(await screen.findByText(/reset link is on its way/)).toBeOnTheScreen();
  });

  it('forgot password shows errors inline', async () => {
    mockAuth.resetPassword.mockResolvedValue({ data: null, error: { message: 'Email rate limit exceeded' } });
    await render(<SignInScreen />);
    await press('Forgot password?');
    await type('Email', 'ada@example.com');
    await press('Send reset link');
    expect(await screen.findByText('Email rate limit exceeded')).toBeOnTheScreen();
  });

  it('shows Google and Apple where Apple is supported', async () => {
    await render(<SignInScreen />);
    expect(screen.getByRole('button', { name: 'Continue with Google' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Continue with Apple' })).toBeEnabled();
  });

  it('hides Apple where it is not offered (Android)', async () => {
    mockShowApple = false;
    await render(<SignInScreen />);
    expect(screen.getByRole('button', { name: 'Continue with Google' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Continue with Apple' })).toBeNull();
  });

  it('starts provider sign-in and shows provider errors inline', async () => {
    mockProvider.mockResolvedValue({ ok: false, error: "Google sign-in isn't set up for this FilmFuse server yet." });
    await render(<SignInScreen />);
    await press('Continue with Google');
    expect(mockProvider).toHaveBeenCalledWith('google');
    expect(await screen.findByText(/Google sign-in isn't set up/)).toBeOnTheScreen();
  });

  it('stays quiet when the user cancels a provider sign-in', async () => {
    mockProvider.mockResolvedValue({ ok: false, cancelled: true });
    await render(<SignInScreen />);
    await press('Continue with Apple');
    await waitFor(() => expect(mockProvider).toHaveBeenCalledWith('apple'));
    expect(screen.queryByTestId('auth-error')).toBeNull();
  });
});
