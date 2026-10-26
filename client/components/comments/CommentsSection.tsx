import { useMemo, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { TextInput } from '../ui/TextInput';
import { useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import {
  COMMENT_MAX,
  flattenComments,
  isPendingComment,
  useComments,
  useCreateComment,
  useDeleteComment,
} from '../../api/comments';
import { ApiError } from '../../lib/apiClient';
import { UNSAFE_TEXT_MESSAGE, isSafeText } from '../../lib/safeText';
import { personName, timeAgo } from '../../lib/format';
import { useTheme } from '../../theme/useTheme';
import type { CommentDTO } from '../../types/api';
import { ReportSheet } from '../moderation/ReportSheet';
import { Avatar, Body, Button, ConfirmSheet, ErrorState, Heading, Skeleton, errorMessage } from '../ui';

/** Problem with a draft comment, or null when it can be sent. */
export function validateComment(draft: string): string | null {
  const trimmed = draft.trim();
  if (!trimmed) return 'Write something first.';
  if (trimmed.length > COMMENT_MAX) return `Keep it under ${COMMENT_MAX} characters.`;
  if (!isSafeText(trimmed, true)) return UNSAFE_TEXT_MESSAGE;
  return null;
}

type RowProps = {
  comment: CommentDTO;
  onDelete: () => void;
  onReport: () => void;
};

function CommentRow({ comment, onDelete, onReport }: RowProps) {
  const router = useRouter();
  const { colors } = useTheme();
  const pending = isPendingComment(comment);
  const name = comment.mine ? 'You' : personName(comment.author);
  const when = pending ? 'Posting…' : timeAgo(comment.createdAt);

  return (
    <View
      testID={`comment-${comment.id}`}
      className={`flex-row gap-3 ${pending ? 'opacity-60' : ''}`}
      accessibilityState={{ busy: pending }}
    >
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${name}'s profile`}
        disabled={pending || !comment.author.id}
        onPress={() => router.push(`/user/${comment.author.id}`)}
      >
        <Avatar
          uri={comment.author.profileImageUrl}
          displayName={comment.author.displayName}
          username={comment.author.username}
          size={32}
        />
      </Pressable>
      <View className="flex-1 gap-0.5">
        <View className="flex-row items-center gap-2">
          <Text className="font-body-semibold text-sm text-text" numberOfLines={1}>
            {name}
          </Text>
          {when ? <Text className="font-body text-xs text-muted">{when}</Text> : null}
        </View>
        <Body testID={`comment-body-${comment.id}`}>{comment.body}</Body>
      </View>
      {!pending && comment.canDelete ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={comment.mine ? 'Delete your comment' : `Delete ${name}'s comment`}
          hitSlop={8}
          onPress={onDelete}
          className="h-9 w-9 items-center justify-center rounded-full active:opacity-70"
        >
          <Ionicons name="trash-outline" size={18} color={colors.muted} />
        </Pressable>
      ) : null}
      {!pending && !comment.mine ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`Options for ${name}'s comment`}
          hitSlop={8}
          onPress={onReport}
          className="h-9 w-9 items-center justify-center rounded-full active:opacity-70"
        >
          <Ionicons name="ellipsis-horizontal" size={18} color={colors.muted} />
        </Pressable>
      ) : null}
    </View>
  );
}

/** "Add a comment" with a live character count; the draft comes back if posting fails. */
function Composer({ ratingId }: { ratingId: string }) {
  const { colors } = useTheme();
  const create = useCreateComment(ratingId);
  const [draft, setDraft] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  const send = () => {
    const issue = validateComment(draft);
    if (issue) {
      setProblem(issue);
      return;
    }
    const body = draft;
    setDraft('');
    create.mutate(body, {
      // Give the text back so nothing typed is lost.
      onError: () => setDraft((current) => (current ? current : body)),
    });
  };

  const serverError =
    create.error instanceof ApiError && Object.keys(create.error.fieldErrors).length
      ? Object.values(create.error.fieldErrors).join(' ')
      : create.isError
        ? errorMessage(create.error)
        : null;
  const error = problem ?? serverError;
  const empty = draft.trim().length === 0;

  return (
    <View testID="comment-composer" className="gap-2">
      <TextInput
        accessibilityLabel="Add a comment"
        value={draft}
        onChangeText={(text) => {
          setProblem(null);
          create.reset();
          setDraft(text);
        }}
        maxLength={COMMENT_MAX}
        multiline
        placeholder="Add a comment"
        placeholderTextColor={colors.muted}
        className="min-h-16 rounded-xl bg-raised p-3 font-body text-base text-text"
        textAlignVertical="top"
      />
      <View className="flex-row items-center justify-between gap-3">
        <Text testID="comment-counter" className="font-body text-xs text-muted">
          {draft.length}/{COMMENT_MAX}
        </Text>
        <Button size="sm" title="Post" accessibilityLabel="Post comment" disabled={empty} onPress={send} />
      </View>
      {error ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          {error}
        </Body>
      ) : null}
    </View>
  );
}

/** A rating's comments, oldest first, with paging, delete (yours / on your rating), report and a composer. */
export function CommentsSection({ ratingId }: { ratingId: string }) {
  const comments = useComments(ratingId);
  const remove = useDeleteComment(ratingId);
  const [deleting, setDeleting] = useState<CommentDTO | null>(null);
  const [reporting, setReporting] = useState<CommentDTO | null>(null);
  const items = useMemo(() => flattenComments(comments.data), [comments.data]);

  let list;
  if (comments.isPending) {
    list = (
      <View testID="comments-loading" className="gap-3">
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-12 w-full" />
      </View>
    );
  } else if (comments.isError && items.length === 0) {
    list = (
      <ErrorState
        compact
        title="Couldn't load comments"
        error={comments.error}
        onRetry={() => void comments.refetch()}
        retrying={comments.isFetching}
      />
    );
  } else if (items.length === 0) {
    list = (
      <Body muted testID="comments-empty">
        No comments yet. Start the conversation.
      </Body>
    );
  } else {
    list = (
      <View testID="comments-list" className="gap-4">
        {items.map((c) => (
          <CommentRow
            key={c.id}
            comment={c}
            onDelete={() => {
              remove.reset();
              setDeleting(c);
            }}
            onReport={() => setReporting(c)}
          />
        ))}
        {comments.hasNextPage ? (
          <Button
            size="sm"
            variant="ghost"
            title="Show more comments"
            loading={comments.isFetchingNextPage}
            onPress={() => void comments.fetchNextPage()}
          />
        ) : null}
        {comments.isError ? (
          <Body accessibilityRole="alert" className="text-sm text-danger">
            {errorMessage(comments.error)}
          </Body>
        ) : null}
      </View>
    );
  }

  return (
    <View testID="comments" className="gap-4">
      <Heading size="sm">Comments</Heading>
      {list}
      {remove.isError ? (
        <Body testID="comment-delete-error" accessibilityRole="alert" className="text-sm text-danger">
          Couldn&apos;t delete the comment. {errorMessage(remove.error)}
        </Body>
      ) : null}
      <Composer ratingId={ratingId} />
      <ConfirmSheet
        visible={deleting !== null}
        title="Delete this comment?"
        message="It's removed for everyone. This can't be undone."
        confirmLabel="Delete"
        danger
        onConfirm={() => {
          if (deleting) remove.mutate(deleting.id);
          setDeleting(null);
        }}
        onClose={() => setDeleting(null)}
      />
      {reporting ? (
        <ReportSheet
          visible
          onClose={() => setReporting(null)}
          menuTitle={`${personName(reporting.author)}'s comment`}
          targetType="COMMENT"
          targetId={reporting.id}
        />
      ) : null}
    </View>
  );
}
