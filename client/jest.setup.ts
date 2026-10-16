/**
 * Vector icons load their font through expo-font/expo-asset, which has no asset registry under
 * jest. Render icons as plain Views carrying the icon name instead.
 */
jest.mock('@expo/vector-icons/Ionicons', () => {
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  const React = jest.requireActual<typeof import('react')>('react');
  const Icon = ({ name, testID }: { name: string; testID?: string }) =>
    React.createElement(View, { testID: testID ?? `icon-${name}` });
  return { __esModule: true, default: Icon };
});

/** Safe-area insets without a native provider. */
jest.mock('react-native-safe-area-context', () => jest.requireActual('react-native-safe-area-context/jest/mock').default);
