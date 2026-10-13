# VoiceOver pass (Safari, ~10 minutes)

Start the demo console (no backend needed):

    python3 -m http.server 8093 --bind 127.0.0.1 --directory modules/http/src/main/resources/sanctity/ui

Open http://127.0.0.1:8093/?demo in Safari. Turn VoiceOver on with Cmd-F5.
Keys: VO = Ctrl-Option. VO-Right/Left reads next/previous; Tab moves between controls;
VO-U opens the rotor (headings, links, form controls); VO-Space activates.

For each step, note: what was read, anything read twice or not at all, and anything confusing.

1. Connect. Tab to the token field. Is it announced as a password field with a label?
   Press Enter with it empty. Is the error read out?
   Type anything, Tab to Dataset, connect.
2. Release page. Open the rotor (VO-U) → Headings. Do the headings make sense on their own
   (dataset title, Files / Assessment / Relationships)? Is "Admitted" read?
3. File tree. Tab into the tree, open sub-02 → func → the nback bold file.
   Is each folder announced as expanded/collapsed? After choosing the file, is the change announced,
   and can you reach the inspector without tabbing through the whole tree?
4. Inspector. VO-Right through Effective metadata. Units: "1.5 s" should be read with a pause or as "1.5 seconds",
   not "1.5s". File names should be read once, not twice.
5. Assessment tab. Are finding groups read as "N findings" with the code and message?
   Expand "recorded facts" — is the expanded/collapsed state announced?
6. Correction. From the bold file choose "Add sidecar". Leave the reason empty and press Prepare preview.
   Are the problems announced, and does focus move to them?
   Fill a reason, prepare the preview: is "Resolved"/"New" read next to each finding?
7. Access. Create a grant with any UUID-looking value, then revoke it. Is the result announced
   (the live region at the bottom of the page)?

Report back whatever sounded wrong; exact wording helps most.
