# Design QA

## Scope

Top-right toolbar: GitHub, language switch, and light/dark theme switch.

## Reference capture

Blocked: the in-app browser timed out while capturing the visual reference at `https://maaend.com/`. Its visible page structure confirmed GitHub as a top-level link, but a screenshot comparison could not be completed.

## Implementation checks

- The toolbar uses three compact, keyboard-focusable controls.
- GitHub uses the existing admin-configured URL and is disabled until configured.
- Language and theme state are persisted in local storage.
- Light and dark themes use semantic CSS variables and retain visible focus/hover states.
- Small-screen styles keep the tool group visible while hiding the less critical new-chat action.

## Final result

blocked — browser visual comparison against the reference could not be completed in this environment.
