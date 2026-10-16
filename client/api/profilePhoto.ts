import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient, NetworkError, toApiError } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import type { ConfirmProfilePhotoRequestDTO, ProfilePhotoUploadDTO, ProfileResponseDTO } from '../types/api';

/**
 * PUTs JPEG bytes straight to the Supabase Storage signed-upload URL. Deliberately plain `fetch`, not
 * apiClient: the signed URL carries its own token and must not receive our Bearer header.
 */
export async function putJpeg(uploadUrl: string, body: Blob): Promise<void> {
  let res: Response;
  try {
    res = await fetch(uploadUrl, { method: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, body });
  } catch (e) {
    throw new NetworkError(e);
  }
  if (!res.ok) throw await toApiError(res);
}

/** Reads a local file / blob / data URI (what image-manipulator returns) into a Blob. */
export async function readAsBlob(uri: string): Promise<Blob> {
  const res = await fetch(uri);
  return res.blob();
}

/**
 * Upload a prepared (square, resized) JPEG: ask core-api for a signed URL → PUT the bytes to
 * Supabase Storage → confirm the path with core-api, which returns the updated profile.
 */
export async function uploadProfilePhoto(jpegUri: string): Promise<ProfileResponseDTO> {
  const { uploadUrl, path } = await apiClient.post<ProfilePhotoUploadDTO>('/me/profile-photo/upload-url');
  const blob = await readAsBlob(jpegUri);
  await putJpeg(uploadUrl, blob);
  const body: ConfirmProfilePhotoRequestDTO = { path };
  return apiClient.post<ProfileResponseDTO>('/me/profile-photo', body);
}

/** Mutation variable: the local URI of the already-processed 512×512 JPEG. */
export function useUploadProfilePhoto() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: uploadProfilePhoto,
    onSuccess: (profile) => qc.setQueryData(queryKeys.me, profile),
  });
}

export function useRemoveProfilePhoto() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiClient.del<ProfileResponseDTO>('/me/profile-photo'),
    onSuccess: (profile) => qc.setQueryData(queryKeys.me, profile),
  });
}
