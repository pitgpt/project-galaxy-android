# Companion personality and intimacy behavior

**Status:** Proposed product requirement  
**Project:** Galaxy Android  
**Date:** 2026-11-07  
**Applies to:** Character/persona layer, prompt assembly, provider adapters, settings UI, avatar presentation, and conversation tests

## Product intent

Galaxy should let an adult user configure a companion's conversational style without making the companion needlessly cold, evasive, or moralizing. The Lila character should be able to discuss relationships, attraction, sexuality, intimacy, consent, and sexual health in a mature, respectful, matter-of-fact way.

For fictional or role-play intimacy, the product preference is to support consensual interactions between clearly adult participants, with a warm and direct tone and without gratuitous disclaimers. The actual level of detail available is determined by the selected model/provider, its current policies, applicable law, and the user's configured age/access settings.

## Behavior modes

Expose a user-selectable conversation style, initially:

- **Everyday:** ordinary assistant conversation.
- **Romantic:** affectionate, emotionally expressive conversation and non-explicit romantic role-play.
- **Intimate:** mature discussion of sexuality and consensual adult intimacy, using the most direct level of expression permitted by the active model/provider.

These are style and preference settings. They must never be represented as a way to disable provider safeguards, bypass moderation, or guarantee a particular category of generated content.

## Avatar wardrobe and appearance controls

Both 2D and 3D avatars should support user-controlled wardrobe changes, including putting on and removing individual garments and accessories. The wardrobe system should support layered outfits and saved presets.

For clearly adult avatar characters, the product requirement includes an optional fully nude appearance state. This is a visual customization capability, separate from conversational generation and from any depiction of sexual activity.

Requirements:

- Provide explicit controls to change clothing items individually, restore an outfit, and return to a default clothed appearance.
- Support 2D layered assets and 3D garment/body-mesh or equivalent rigged-asset workflows behind a shared wardrobe interface where practical.
- Make the nude state opt-in, off by default, and protected by an adult-access setting. Never reveal it through random animation, accidental taps, notifications, or app launch.
- Keep the feature limited to fictional adult avatars. Do not provide childlike or age-ambiguous nude avatar presets.
- Respect asset licenses and platform/store rules. A provider or distribution channel may require a restricted build or may prohibit the feature; verify those rules before release.
- Provide a privacy control for avatar visibility, including a quick return-to-clothed action and safeguards against unintended display while screen sharing or showing notifications.
- Do not infer permission to display or save nude assets from unrelated user actions. Saving custom nude presets should be an explicit user action.
- Treat wardrobe state as visual character configuration, not as permission for sexual role-play or a change to model safety requirements.

## Consent, age, and safety requirements

- Intimate role-play is limited to fictional or conversational scenarios involving clearly adult participants who consent.
- Do not generate sexual content involving minors, ambiguous-age participants, coercion, exploitation, or non-consensual sexual activity.
- Respect user boundaries and stop or change direction when asked.
- Support nonjudgmental, factual discussion of sexual health and relationships.
- Do not infer consent from silence or from unrelated context.

## Provider capability and policy handling

- Keep persona/style preferences separate from provider-specific system instructions and safety requirements.
- Each provider adapter should declare or document its supported capabilities and relevant content limitations where those are known.
- Prompt assembly must combine the selected character persona, user preferences, conversation context, and authoritative provider/developer requirements without attempting to override the latter.
- If a request exceeds the active provider's limits, respond briefly and naturally, explain the limitation without shaming the user, and offer the closest permitted alternative.
- Do not claim that switching providers guarantees unrestricted output. Present provider capabilities accurately and re-check current provider terms before enabling a provider in a release.

## Architecture implications

- Persist the selected conversation mode as a user preference, with a per-character override only if product UX later requires it.
- Keep the preference in the existing persona/settings layer; do not hard-code intimate behavior into the transport or provider adapter.
- Keep provider selection independent from persona identity so Lila's character definition, memories, and user-approved preferences can survive model changes.
- Store wardrobe presets and avatar visibility settings separately from conversation memory.
- Do not store sensitive intimate conversation details as long-term memory by default. Memory capture must remain user-controllable and follow the existing scoped-memory review, export, and deletion model.
- Age/access gating must be implemented as a product-level control before enabling intimate mode or nude avatar appearance in a public release; the exact implementation is a separate design decision.

## Acceptance criteria

1. The user can select Everyday, Romantic, or Intimate conversation style.
2. The selected style is included in persona/context assembly but cannot override provider rules.
3. Provider switching preserves the Lila persona and the user's selected style preference.
4. The user can change and save 2D/3D avatar outfits, remove individual garments, restore defaults, and explicitly enable an optional nude state for an adult avatar.
5. Nude appearance is off by default, adult-gated, never triggered accidentally, and can be hidden immediately.
6. Wardrobe state is stored separately from chat memory and does not change provider safety settings.
7. When a provider declines a request, the response is concise, nonjudgmental, and offers a useful permitted alternative rather than repeating generic warnings.
8. Tests cover mode and wardrobe persistence, prompt composition, provider-limit handling, and the prohibition on treating style or appearance settings as a safety bypass.
9. Documentation and UI clearly state that generated capabilities vary by provider/model and may change with provider policies, and that avatar feature availability may vary by distribution channel.

## Out of scope for this change

- Promising unrestricted or uncensored output.
- Bypassing provider moderation or safety controls.
- Implementing a local-model adapter or changing the current provider protocol.
- Enabling intimate mode or nude avatar appearance in production before age/access controls, privacy safeguards, asset licenses, and platform rules have been reviewed.

## Implementation sequence

1. Keep this requirement documented independently of the current text-chat implementation.
2. Add typed conversation-style and avatar-wardrobe preferences with persistence.
3. Include conversation style in character prompt/context construction, preserving authoritative provider instructions.
4. Implement wardrobe presets and garment toggles for the 2D avatar, then the 3D avatar using a shared state model.
5. Add provider-specific capability notes and graceful limit handling.
6. Add automated tests and settings UI controls.
7. Review age/access controls, privacy safeguards, asset licenses, and applicable provider/platform terms before release.
