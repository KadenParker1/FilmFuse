import type { ReactNode } from 'react';
import { ScrollView, View } from 'react-native';
import { useSafeAreaInsets, type Edge } from 'react-native-safe-area-context';

type Props = {
  children: ReactNode;
  scroll?: boolean;
  /** Safe-area edges to pad. Tab screens skip the bottom (the tab bar handles it). */
  edges?: Edge[];
  className?: string;
  /** Grid screens (Discover) use a wider max width on big web screens. */
  wide?: boolean;
};

/** Safe-area page wrapper with the app background and a readable max width on wide web. */
export function Screen({ children, scroll = false, edges = ['top', 'bottom'], className = '', wide = false }: Props) {
  const insets = useSafeAreaInsets();
  const padding = {
    paddingTop: edges.includes('top') ? insets.top : 0,
    paddingBottom: edges.includes('bottom') ? insets.bottom : 0,
    paddingLeft: edges.includes('left') ? insets.left : 0,
    paddingRight: edges.includes('right') ? insets.right : 0,
  };
  const inner = <View className={`w-full ${wide ? 'max-w-6xl' : 'max-w-3xl'} flex-1 self-center px-5 py-6 ${className}`}>{children}</View>;

  return (
    <View className="flex-1 bg-bg" style={padding}>
      {scroll ? (
        <ScrollView contentContainerClassName="flex-grow" keyboardShouldPersistTaps="handled">
          {inner}
        </ScrollView>
      ) : (
        inner
      )}
    </View>
  );
}
