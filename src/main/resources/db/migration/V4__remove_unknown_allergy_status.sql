-- An old "unknown" answer is not evidence that a user has no allergies.
-- Keep any existing allergy rows intact and request a new choice on onboarding.
UPDATE user_preferences SET allergy_status = 'UNANSWERED' WHERE allergy_status = 'UNKNOWN';
