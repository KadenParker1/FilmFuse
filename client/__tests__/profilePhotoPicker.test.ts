const mockSave = jest.fn();
const mockRender = jest.fn();
const mockResize = jest.fn();
jest.mock('expo-image-picker', () => ({
  requestCameraPermissionsAsync: jest.fn(),
  launchCameraAsync: jest.fn(),
  launchImageLibraryAsync: jest.fn(),
}));
jest.mock('expo-image-manipulator', () => ({
  SaveFormat: { JPEG: 'jpeg' },
  ImageManipulator: { manipulate: jest.fn(() => ({ resize: mockResize })) },
}));

import * as ImagePicker from 'expo-image-picker';
import { ImageManipulator } from 'expo-image-manipulator';
import { canUseCamera, pickSquarePhoto, prepareProfilePhoto } from '../components/profile/profilePhoto';

const picker = ImagePicker as unknown as Record<string, jest.Mock>;

beforeEach(() => {
  jest.clearAllMocks();
  mockResize.mockReturnValue({ renderAsync: mockRender });
  mockRender.mockResolvedValue({ saveAsync: mockSave });
  mockSave.mockResolvedValue({ uri: 'file:///cache/out.jpg', width: 512, height: 512 });
});

describe('pickSquarePhoto', () => {
  const square = { mediaTypes: ['images'], allowsEditing: true, aspect: [1, 1], quality: 1 };

  it('opens the library with a square crop', async () => {
    picker.launchImageLibraryAsync!.mockResolvedValue({ canceled: false, assets: [{ uri: 'file:///a.jpg' }] });
    await expect(pickSquarePhoto('library')).resolves.toEqual({ uri: 'file:///a.jpg' });
    expect(picker.launchImageLibraryAsync).toHaveBeenCalledWith(square);
    expect(picker.requestCameraPermissionsAsync).not.toHaveBeenCalled();
  });

  it('asks for camera permission first', async () => {
    picker.requestCameraPermissionsAsync!.mockResolvedValue({ granted: true });
    picker.launchCameraAsync!.mockResolvedValue({ canceled: false, assets: [{ uri: 'file:///c.jpg' }] });
    await expect(pickSquarePhoto('camera')).resolves.toEqual({ uri: 'file:///c.jpg' });
    expect(picker.launchCameraAsync).toHaveBeenCalledWith(square);
  });

  it('explains a denied camera permission', async () => {
    picker.requestCameraPermissionsAsync!.mockResolvedValue({ granted: false });
    await expect(pickSquarePhoto('camera')).resolves.toEqual({ error: expect.stringContaining('camera access') });
    expect(picker.launchCameraAsync).not.toHaveBeenCalled();
  });

  it('reports cancellation', async () => {
    picker.launchImageLibraryAsync!.mockResolvedValue({ canceled: true, assets: null });
    await expect(pickSquarePhoto('library')).resolves.toEqual({ cancelled: true });
  });

  it('offers the camera on native', () => {
    expect(canUseCamera).toBe(true);
  });
});

describe('prepareProfilePhoto', () => {
  it('resizes to 512x512 and saves a 0.8 JPEG', async () => {
    await expect(prepareProfilePhoto('file:///a.jpg')).resolves.toBe('file:///cache/out.jpg');
    expect(ImageManipulator.manipulate).toHaveBeenCalledWith('file:///a.jpg');
    expect(mockResize).toHaveBeenCalledWith({ width: 512, height: 512 });
    expect(mockSave).toHaveBeenCalledWith({ format: 'jpeg', compress: 0.8 });
  });
});
