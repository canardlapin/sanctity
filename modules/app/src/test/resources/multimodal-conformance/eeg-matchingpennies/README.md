# Matching Pennies EEG example

A published BIDS EEG study, copied unchanged from the public BIDS examples. It is a
software fixture: its recordings are empty placeholders, and its names, sidecars and
tables are real.

- Source: <https://github.com/bids-standard/bids-examples>, directory `eeg_matchingpennies`,
  commit `5e00d47b6f1db106dda1b776d5243eb41b4cb95b` (9 October 2026).
- Licence: PDDL, as stated in `dataset/dataset_description.json` and `dataset/LICENSE`.
- Citation: Appelhoff, S., Sauer, D., & Gill, S. S. (2018). Matching Pennies: A Brain Computer
  Interface Implementation Dataset. <https://doi.org/10.17605/OSF.IO/CJ2DR>

It holds BrainVision recordings with channel and event tables, a shared sidecar at the
root, and `sourcedata/`, `code/` and `stimuli/` directories, so one study exercises
described, assessed and preserved content together. `dataset/code/` is stored as data
and is never executed.
