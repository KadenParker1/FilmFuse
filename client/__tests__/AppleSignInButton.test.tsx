jest.mock('expo-apple-authentication', () => {
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    AppleAuthenticationButtonType: { SIGN_IN: 0, CONTINUE: 1 },
    AppleAuthenticationButtonStyle: { WHITE: 0, WHITE_OUTLINE: 1, BLACK: 2 },
    AppleAuthenticationButton: (props: Record<string, unknown>) =>
      React.createElement(View, { testID: 'apple-system-button', ...props }),
  };
});
const mockScheme = { value: 'dark' as 'dark' | 'light' };
jest.mock('../theme/useTheme', () => {
  const { palettes } = jest.requireActual('../theme/tokens');
  return { useTheme: () => ({ scheme: mockScheme.value, colors: palettes[mockScheme.value] }) };
});

import { Platform } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { AppleSignInButton } from '../components/AppleSignInButton';

const setOS = (os: typeof Platform.OS) => Object.defineProperty(Platform, 'OS', { configurable: true, get: () => os });
const originalOS = Platform.OS;

afterEach(() => {
  setOS(originalOS);
  mockScheme.value = 'dark';
});

describe('AppleSignInButton', () => {
  it("uses Apple's system SIGN_IN button on iOS, white on dark, pill-shaped", async () => {
    setOS('ios');
    const onPress = jest.fn();
    await render(<AppleSignInButton onPress={onPress} />);
    const button = screen.getByTestId('apple-system-button');
    expect(button).toHaveProp('buttonType', 0);
    expect(button).toHaveProp('buttonStyle', 0);
    expect(button).toHaveProp('cornerRadius', 24);
    expect(screen.queryByRole('button', { name: 'Continue with Apple' })).toBeNull();
    (button.props.onPress as () => void)();
    expect(onPress).toHaveBeenCalled();
  });

  it('is black on the light scheme and inert while busy', async () => {
    setOS('ios');
    mockScheme.value = 'light';
    await render(<AppleSignInButton onPress={jest.fn()} loading />);
    expect(screen.getByTestId('apple-system-button')).toHaveProp('buttonStyle', 2);
    expect(screen.getByTestId('apple-native-button')).toHaveProp('pointerEvents', 'none');
  });

  it('uses our styled button on web', async () => {
    setOS('web');
    const onPress = jest.fn();
    await render(<AppleSignInButton onPress={onPress} />);
    expect(screen.queryByTestId('apple-system-button')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Continue with Apple' }));
    expect(onPress).toHaveBeenCalled();
  });
});
