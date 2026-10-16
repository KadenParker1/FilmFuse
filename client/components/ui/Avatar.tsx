import { useState } from 'react';
import { Text, View } from 'react-native';
import { Image } from 'expo-image';
import { initialsFor } from './initials';

type Props = {
  uri?: string | null;
  displayName?: string | null;
  username?: string | null;
  size?: number;
  className?: string;
};

/** Round profile photo, falling back to gold-on-velvet initials when there's no photo (or it fails). */
export function Avatar({ uri, displayName, username, size = 64, className = '' }: Props) {
  const [failedUri, setFailedUri] = useState<string | null>(null);
  const showImage = !!uri && failedUri !== uri;
  const name = displayName || username || 'Member';

  return (
    <View
      accessible
      accessibilityRole="image"
      accessibilityLabel={`${name}'s profile photo`}
      className={`items-center justify-center overflow-hidden rounded-full border-2 border-gold bg-velvet ${className}`}
      style={{ width: size, height: size }}
    >
      {showImage ? (
        <Image
          testID="avatar-image"
          source={{ uri }}
          style={{ width: size, height: size }}
          contentFit="cover"
          transition={150}
          onError={() => setFailedUri(uri)}
        />
      ) : (
        <Text testID="avatar-initials" className="font-display-bold text-on-velvet" style={{ fontSize: size * 0.38 }}>
          {initialsFor(displayName, username)}
        </Text>
      )}
    </View>
  );
}
