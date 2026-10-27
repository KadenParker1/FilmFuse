import { useState } from 'react';
import { Text, View } from 'react-native';
import { TextInput } from '../ui/TextInput';
import Ionicons from '@expo/vector-icons/Ionicons';
import { REPORT_DETAILS_MAX, useCreateReport } from '../../api/reports';
import { ApiError } from '../../lib/apiClient';
import { useTheme } from '../../theme/useTheme';
import type { ReportReason, ReportTargetType } from '../../types/api';
import { Body, Button, ChoiceGroup, Heading, Sheet, errorMessage, type Choice } from '../ui';

export const REASON_CHOICES: Choice<ReportReason>[] = [
  { value: 'SPAM', label: 'Spam' },
  { value: 'HARASSMENT', label: 'Harassment' },
  { value: 'HATE', label: 'Hate' },
  { value: 'SEXUAL_CONTENT', label: 'Sexual content' },
  { value: 'SPOILERS', label: 'Unmarked spoilers' },
  { value: 'OTHER', label: 'Something else' },
];

/** What's being reported, as the copy names it. */
const NOUN: Record<ReportTargetType, string> = { RATING: 'rating', COMMENT: 'comment', USER: 'person' };

export const REPORT_THANKS = 'Thanks for letting us know';

type FormProps = {
  targetType: ReportTargetType;
  targetId: string;
  /** Closes whatever holds the form (Cancel, or Done after reporting). */
  onDone: () => void;
};

/** Pick a reason, optionally add details, send; then a thank-you. Never an alert(). */
export function ReportForm({ targetType, targetId, onDone }: FormProps) {
  const { colors } = useTheme();
  const report = useCreateReport();
  const [reason, setReason] = useState<ReportReason | null>(null);
  const [details, setDetails] = useState('');
  const noun = NOUN[targetType];

  if (report.isSuccess) {
    return (
      <View testID="report-success" className="items-center gap-3">
        <Ionicons name="checkmark-circle" size={44} color={colors.accent} />
        <Heading size="sm" className="text-center" accessibilityLiveRegion="polite">
          {REPORT_THANKS}
        </Heading>
        <Body muted className="text-center">
          We&apos;ll take a look. If you&apos;d rather not hear from someone at all, you can block them from their
          profile.
        </Body>
        <Button title="Done" className="w-full" onPress={onDone} />
      </View>
    );
  }

  const tooLong = details.length > REPORT_DETAILS_MAX;
  const submit = () => {
    if (!reason || tooLong) return;
    report.mutate({ targetType, targetId, reason, details });
  };
  const failure =
    report.error instanceof ApiError && report.error.status === 404
      ? `This ${noun} isn't available anymore.`
      : report.error instanceof ApiError && Object.keys(report.error.fieldErrors).length
        ? Object.values(report.error.fieldErrors).join(' ')
        : report.isError
          ? errorMessage(report.error)
          : null;

  return (
    <View testID="report-form" className="gap-4">
      <Heading size="sm">Report this {noun}</Heading>
      <Body muted className="text-sm">
        Reports are private: they aren&apos;t told who sent it.
      </Body>
      <ChoiceGroup
        label="Reason"
        choices={REASON_CHOICES}
        value={reason}
        disabled={report.isPending}
        onChange={(r) => {
          report.reset();
          setReason(r);
        }}
      />
      <View className="gap-1">
        <TextInput
          accessibilityLabel="Details (optional)"
          value={details}
          onChangeText={setDetails}
          maxLength={REPORT_DETAILS_MAX}
          editable={!report.isPending}
          multiline
          placeholder="Anything that helps us understand (optional)"
          placeholderTextColor={colors.muted}
          className="min-h-20 rounded-xl bg-raised p-3 font-body text-base text-text"
          textAlignVertical="top"
        />
        <Text testID="report-details-counter" className="self-end font-body text-xs text-muted">
          {details.length}/{REPORT_DETAILS_MAX}
        </Text>
      </View>
      {!reason ? (
        <Body muted testID="report-reason-hint" className="text-sm">
          Pick a reason to send the report.
        </Body>
      ) : null}
      {failure ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          {failure}
        </Body>
      ) : null}
      <Button
        title="Send report"
        variant="danger"
        loading={report.isPending}
        disabled={!reason || tooLong}
        onPress={submit}
      />
      <Button title="Cancel" variant="ghost" disabled={report.isPending} onPress={onDone} />
    </View>
  );
}

type SheetProps = Omit<FormProps, 'onDone'> & {
  visible: boolean;
  onClose: () => void;
  /**
   * When set, the sheet opens as an overflow menu with this title and a "Report …" entry; otherwise
   * it opens straight on the form.
   */
  menuTitle?: string;
};

/** A bottom sheet for reporting a rating, comment or person (optionally behind a small menu). */
export function ReportSheet({ visible, onClose, menuTitle, ...target }: SheetProps) {
  const [step, setStep] = useState<'menu' | 'form'>(menuTitle ? 'menu' : 'form');
  const close = () => {
    setStep(menuTitle ? 'menu' : 'form');
    onClose();
  };
  return (
    <Sheet visible={visible} onClose={close} title={step === 'menu' ? menuTitle : undefined}>
      {!visible ? null : step === 'menu' ? (
        <View className="gap-3">
          <Button title={`Report ${NOUN[target.targetType]}`} variant="danger" onPress={() => setStep('form')} />
          <Button title="Cancel" variant="ghost" onPress={close} />
        </View>
      ) : (
        <ReportForm {...target} onDone={close} />
      )}
    </Sheet>
  );
}
