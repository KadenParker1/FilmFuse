import { Platform } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';

export type PhotoSource = 'library' | 'camera';

/** The camera option is offered on native only; web has the file chooser. */
export const canUseCamera = Platform.OS !== 'web';

export const PHOTO_SIZE = 512;
export const PHOTO_QUALITY = 0.8;

export type PickResult = { uri: string } | { cancelled: true } | { error: string };

/** Opens the library or camera with a square crop. */
export async function pickSquarePhoto(source: PhotoSource): Promise<PickResult> {
  const options: ImagePicker.ImagePickerOptions = {
    mediaTypes: ['images'],
    allowsEditing: true,
    aspect: [1, 1],
    quality: 1,
  };
  if (source === 'camera') {
    const perm = await ImagePicker.requestCameraPermissionsAsync();
    if (!perm.granted) return { error: 'FilmFuse needs camera access to take a photo. You can allow it in Settings.' };
  }
  const result =
    source === 'camera' ? await ImagePicker.launchCameraAsync(options) : await ImagePicker.launchImageLibraryAsync(options);
  const asset = result.canceled ? undefined : result.assets[0];
  if (!asset) return { cancelled: true };
  return { uri: asset.uri };
}

/** Resizes to a 512×512 JPEG at 0.8 quality and returns its local URI (a data/blob URI on web). */
export async function prepareProfilePhoto(uri: string): Promise<string> {
  const context = ImageManipulator.manipulate(uri).resize({ width: PHOTO_SIZE, height: PHOTO_SIZE });
  const image = await context.renderAsync();
  const saved = await image.saveAsync({ format: SaveFormat.JPEG, compress: PHOTO_QUALITY });
  return saved.uri;
}
