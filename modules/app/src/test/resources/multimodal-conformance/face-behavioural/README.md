# Acquired behavioural response excerpt

This fixture retains eight response rows for subject 002 from the CC0 Face
processing MEEG dataset in the official BIDS examples corpus. The
[pinned source table](https://github.com/bids-standard/bids-examples/blob/5e00d47b6f1db106dda1b776d5243eb41b4cb95b/eeg_ds003645s_hed_demo/sub-002/ses-1/beh/sub-002_ses-1_task-FaceRecognition_beh.tsv)
contains 298 rows. Its acquisition reference is
[Wakeman and Henson 2015](https://doi.org/10.1038/sdata.2015.1).

`source.json` binds the source revision, URLs, hashes, license evidence and
transformations. `originals/` preserves the complete source table, description and
column dictionary. `dataset/` contains the first eight rows with their original
CRLF/tab/decimal bytes, the original description and dictionary, and the eight
referenced stimulus entries. No response values are rounded or synthesized.
All other modalities and unselected rows/dependencies are excluded explicitly.

The stimulus images are **empty placeholders in the upstream example corpus**.
Their original zero-byte content is retained. This fixture qualifies acquired
response-table handling, dependency inventory, byte preservation and opaque HED
metadata retention. It does not qualify image display or a complete acquired
multimodal study. HED annotations and their source version are preserved without
claiming HED semantic validation.

The shared `CatalogBehaviouralSuite` imports and projects this excerpt on both
catalogs, requires explicit source HED-version disclosure, and checks response
count, representative lexical values, primary-table bytes and dependency count.
