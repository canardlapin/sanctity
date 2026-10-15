/* Demo transport for the Sanctity console. Loaded only with ?demo; responses follow the /v1 PublicJson shapes.
   Synthetic data with no scientific claims. Findings, checks and metadata are all derived from each release's
   sidecar texts by one interpreter, so a previewed candidate and its accepted release always agree. */
'use strict';
(() => {
  const DS = 'b7e3c1d2-5a4f-4e8b-9c21-6f0a3d9e8b14';
  const ME = 'a3f19c0e-6b2d-4e7a-9f15-8c0d2e4b6a91';
  const R = ['1c9a4e70-2b3d-4f5e-8a61-7d2c9b0e3f45', '5e2f8a13-9c4b-4d7a-b6e0-2a1f3c8d9e57', '9d4b2e61-7a3c-4f18-a5d9-0e6c1b2f8a73'];
  const POLICY = 'reference-admission-v7';
  const hex = seed => {
    let out = '', x = 2166136261;
    for (let round = 0; out.length < 64; round++) {
      for (const ch of seed + ':' + round) x = Math.imul(x ^ ch.charCodeAt(0), 16777619) >>> 0;
      out += x.toString(16).padStart(8, '0');
    }
    return out.slice(0, 64);
  };
  const uuid = () => crypto.randomUUID ? crypto.randomUUID() : [8, 4, 4, 4, 12].map(n => hex(String(Math.random())).slice(0, n)).join('-');
  const utf8 = s => new TextEncoder().encode(s);
  const json = o => JSON.stringify(o, null, 2) + '\n';

  // ---- fixed acquisition facts (what the image headers say) ----
  const subjects = [['01', 24, 'F', 'control'], ['02', 31, 'M', 'control'], ['03', 27, 'F', 'patient'], ['04', 38, 'M', 'patient']];
  const VOLUMES = 270;
  const headerTr = s => s === '02' ? 2.0 : 1.5;  // pixdim[4] of each n-back run
  const restTr = s => s === '04' ? 2.0 : 1.5;    // sub-04's rest run was acquired at 2.0 s and has no override yet
  const noTransform = img => img === 'sub-04/func/sub-04_task-rest_bold.nii.gz';  // header declares neither qform nor sform
  const eventOnsets = s => {
    const on = Array.from({ length: 48 }, (_, i) => +(6 + i * 8.25).toFixed(2));
    return s === '03' ? [...on, 405.75, 406.5] : on;
  };

  // ---- release 1 texts ----
  const base = {
    'dataset_description.json': json({ Name: 'Episodic sequence memory — MRI pilot', BIDSVersion: '1.10.0', DatasetType: 'raw', License: 'CC0', Authors: ['Synthetic Fixture'] }),
    'README': 'Episodic sequence memory MRI pilot.\n\nSynthetic demonstration data for the Sanctity console. Values are invented and\nmake no scientific claims.\n',
    'CHANGES': '1.0.1 2026-09-29\n  - Added the sub-02 n-back RepetitionTime override (header spacing 2.0 s).\n1.0.0 2026-09-18\n  - Initial import.\n',
    'participants.tsv': 'participant_id\tage\tsex\tgroup\n' + subjects.map(([s, a, x, g]) => `sub-${s}\t${a}\t${x}\t${g}`).join('\n') + '\n',
    'participants.json': json({ age: { Description: 'Age at scan', Units: 'year' }, sex: { Description: 'Self-reported sex', Levels: { F: 'female', M: 'male' } }, group: { Description: 'Study arm' } }),
    'task-nback_bold.json': json({ TaskName: 'nback', RepetitionTime: 1.5, EchoTime: 0.03, FlipAngle: 52, PhaseEncodingDirection: 'j-', SliceEncodingDirection: 'k' }),
    'task-rest_bold.json': json({ TaskName: 'rest', RepetitionTime: 1.5, EchoTime: 0.03, FlipAngle: 52 }),
    'sub-02/func/sub-02_task-nback_run-1_bold.json': json({ RepetitionTime: 2.0 }),
  };
  const binaries = {};
  for (const [s] of subjects) {
    const p = `sub-${s}/`;
    base[`${p}anat/sub-${s}_T1w.json`] = json({ RepetitionTime: 2.3, InversionTime: 0.9, EchoTime: 0.00298, FlipAngle: 9 });
    base[`${p}fmap/sub-${s}_dir-AP_epi.json`] = json({ B0FieldIdentifier: 'pepolar' + s, PhaseEncodingDirection: 'j-', TotalReadoutTime: 0.0581,
      IntendedFor: [`bids::sub-${s}/func/sub-${s}_task-nback_run-1_bold.nii.gz`, `bids::sub-${s}/func/sub-${s}_task-rest_bold.nii.gz`] });
    base[`${p}fmap/sub-${s}_dir-PA_epi.json`] = json({ B0FieldIdentifier: 'pepolar' + s, PhaseEncodingDirection: 'j', TotalReadoutTime: 0.0581 });
    base[`${p}dwi/sub-${s}_dwi.json`] = json({ PhaseEncodingDirection: 'j-', TotalReadoutTime: 0.0581, MultibandAccelerationFactor: 3 });
    base[`${p}dwi/sub-${s}_dwi.bval`] = Array.from({ length: 65 }, (_, i) => i % 13 === 0 ? 0 : 1000).join(' ') + '\n';
    const dir = i => [Math.cos(i * 0.7), Math.sin(i * 0.7) * Math.cos(i * 1.3), Math.sin(i * 0.7) * Math.sin(i * 1.3)];
    base[`${p}dwi/sub-${s}_dwi.bvec`] = [0, 1, 2].map(ax => Array.from({ length: 65 }, (_, i) => i % 13 === 0 ? '0' : dir(i)[ax].toFixed(6)).join(' ')).join('\n') + '\n';
    base[`${p}func/sub-${s}_task-nback_run-1_events.tsv`] = 'onset\tduration\ttrial_type\n' + eventOnsets(s).map((o, i) => `${o}\t2.0\t${['0back', '2back'][i % 2]}`).join('\n') + '\n';
    if (s === '01' || s === '02') base[`${p}beh/sub-${s}_task-recall_beh.tsv`] = 'trial\tresponse\tresponse_time\n' +
      Array.from({ length: 12 }, (_, i) => `${i + 1}\t${['old', 'new'][(i * 7 + +s) % 2]}\t${i === 5 ? 'n/a' : (0.62 + ((i * 37 + +s * 11) % 41) / 100).toFixed(3)}`).join('\n') + '\n';
    binaries[`${p}anat/sub-${s}_T1w.nii.gz`] = 15728640 + +s * 91233;
    binaries[`${p}func/sub-${s}_task-nback_run-1_bold.nii.gz`] = 187695104 + +s * 1021;
    binaries[`${p}func/sub-${s}_task-rest_bold.nii.gz`] = 154140672 + +s * 733;
    binaries[`${p}fmap/sub-${s}_dir-AP_epi.nii.gz`] = 2359296;
    binaries[`${p}fmap/sub-${s}_dir-PA_epi.nii.gz`] = 2359296;
    binaries[`${p}dwi/sub-${s}_dwi.nii.gz`] = 98566144 + +s * 311;
  }
  // Release 1 lacked sub-02's n-back TR override (a recorded warning); release 2 added that sidecar.
  const r1 = { ...base }; delete r1['sub-02/func/sub-02_task-nback_run-1_bold.json'];
  const releases = new Map([
    [R[0], { texts: r1, parent: null, acceptedAt: '2026-09-18T09:12:04Z' }],
    [R[1], { texts: base, parent: R[0], acceptedAt: '2026-09-29T14:47:31Z' }],
    [R[2], { texts: { ...base, 'README': base.README + '\nContact the custodian before sharing outside the lab.\n' }, parent: R[1], acceptedAt: '2026-10-08T16:03:52Z' }],
  ]);
  let head = R[2];

  // ---- interpretation: metadata, relationships, findings, checks from texts ----
  const parse = t => { try { return JSON.parse(t); } catch { return null; } };
  const finding = (code, severity, path, message) => ({ code, severity, path, message });
  const stem = p => p.replace(/\.(nii\.gz|nii|json|tsv|bval|bvec)$/, '');
  // BIDS inheritance: JSON sidecars in the file's directory or above, with the same suffix and a subset of its entities,
  // applied from the shallowest (least specific) to the deepest.
  const nameParts = n => { const parts = stem(n).split('_'); return { suffix: parts.pop(), entities: parts }; };
  function layers(texts, p) {
    const file = p.split('/').pop(), dir = p.slice(0, -file.length), img = nameParts(file);
    const depth = k => k.split('/').length;
    return Object.keys(texts).filter(k => {
      if (!k.endsWith('.json') || k === p) return false;
      const n = k.split('/').pop(), j = nameParts(n);
      return dir.startsWith(k.slice(0, -n.length)) && j.suffix === img.suffix && j.entities.every(e => img.entities.includes(e));
    }).sort((a, b) => depth(a) - depth(b) || nameParts(a).entities.length - nameParts(b).entities.length);
  }
  function metadata(texts, p) {
    if (!/\.nii\.gz$/.test(p) || !(p in binaries)) return null;
    const fields = {}, sources = layers(texts, p);
    for (const src of sources) {
      const doc = parse(texts[src]) || {};
      for (const [k, v] of Object.entries(doc)) {
        const prior = fields[k] ? fields[k].history : [];
        fields[k] = { value: v, origin: src, history: [...prior, { source: src, value: v }] };
      }
    }
    return { path: p, fields, sources };
  }
  // Tables and gradients are read from the release texts, so a corrected document is re-assessed as the backend does.
  // Messages follow bids4s CheckedTsv/CheckedEvents/DwiConsistency: message, then " row R column C".
  const tsvRows = t => t.replace(/\r?\n$/, '').split(/\r?\n/).map(l => l.split('\t'));
  const DECIMAL = /^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)?$/;
  function readEvents(text, path) {
    const issues = [], rows = [];
    const err = (code, message, row, col) => issues.push(finding('Events' + code, 'Error', path, message + ' row ' + row + (col ? ' column ' + col : '')));
    const [cols, ...body] = tsvRows(text);
    ['onset', 'duration'].forEach((name, i) => { if (cols[i] !== name) err('Header', name + ' must be column ' + (i + 1), 1, i + 1); });
    let missingOnset = 0, missingDuration = 0, previous = null;
    body.forEach((cells, i) => {
      const line = i + 2;
      if (cells.length !== cols.length) err('Width', `row has ${cells.length} cells; expected ${cols.length}`, line);
      cells.forEach((v, c) => { if (v === '') err('MissingValue', 'empty cell; use n/a for unavailable values', line, c + 1); });
      const num = c => { const v = cells[c]; if (v == null || v === '' || v === 'n/a') return null;
        if (!DECIMAL.test(v)) { err('Number', 'expected bounded decimal seconds or n/a', line, c + 1); return null; } return Number(v); };
      const onset = cols[0] === 'onset' ? num(0) : null, duration = cols[1] === 'duration' ? num(1) : null;
      if (onset == null) missingOnset++; if (duration == null) missingDuration++;
      if (duration != null && duration < 0) err('Duration', 'duration must be nonnegative or n/a', line, 2);
      if (onset != null && previous != null && previous > onset)
        issues.push(finding('EventsOrder', 'Warning', path, 'known onsets are not sorted row ' + line + ' column 1'));
      if (onset != null) { previous = onset; rows.push([onset, duration || 0, line]); }
    });
    return { issues, rows, columns: cols, missingOnset, missingDuration, valid: !issues.some(f => f.severity === 'Error') };
  }
  function readGradients(bvalText, bvecText, bval, bvec, volumes) {
    const issues = [];
    const values = (t, path, input) => (t || '').replace(/\n$/, '').split('\n').map((l, r) => l.trim().split(/\s+/).filter(Boolean).map((v, c) => {
      if (!DECIMAL.test(v)) issues.push(finding('DwiNumber', 'Error', path, 'expected a finite decimal number row ' + (r + 1) + ' column ' + (c + 1)));
      return Number(v); }));
    const b = values(bvalText, bval).flat(), v = values(bvecText, bvec);
    const vectors = v.length === 3 && v.every(r => r.length === v[0].length) ? v[0].length : 0;
    if (!issues.length && b.length !== volumes) issues.push(finding('DwiBvalCount', 'Error', bval, `${b.length} b-values for ${volumes} image volumes`));
    if (!issues.length && vectors !== volumes) issues.push(finding('DwiBvecCount', 'Error', bvec, `${vectors} vectors for ${volumes} image volumes`));
    return { issues, bvalCount: b.length, bvecCount: vectors };
  }
  const BEH_RULE = 'sanctity-behavioural-v1+bids-quoted-tsv-v1+bids-events-content-v1+bids-stimuli-v1';
  function readBehaviour(text, path) {
    const issues = [];
    const [cols, ...body] = tsvRows(text);
    body.forEach((cells, i) => {
      if (!issues.length && cells.length !== cols.length) issues.push(finding('BehaviouralTable', 'Error', path, `row width differs from header at row ${i + 2}, column 1`));
      const e = cells.indexOf('');
      if (!issues.length && e >= 0) issues.push(finding('BehaviouralTable', 'Error', path, `empty cell; use n/a at row ${i + 2}, column ${e + 1}`));
    });
    const rt = cols.indexOf('response_time');
    if (!issues.length && rt >= 0) body.forEach((cells, i) => { if (cells[rt] !== 'n/a' && !DECIMAL.test(cells[rt]))
      issues.push(finding('BehaviouralResponseTime', 'Error', path, `response_time must be a bounded decimal or n/a at row ${i + 2}`)); });
    return { issues, facts: { recordingKind: 'behavioural', timingBasis: 'unavailable; no recording clock inferred',
      ...(issues.some(f => f.code === 'BehaviouralTable') ? {} : { rowCount: body.length, columns: cols }) } };
  }
  const behavioural = texts => Object.keys(texts).some(k => /(^|\/)beh\/[^/]+_beh\.tsv$/.test(k));

  // Codes, messages, rules and fact names follow the live reference-admission-v7 backend (observed 2026-10-12);
  // behavioural checks follow repository-mri-behavioural-admission-v1.
  function interpret(texts) {
    const findings = [], checks = [], links = [];
    const files = [...Object.keys(texts), ...Object.keys(binaries)];
    const input = (role, path) => ({ role, path, digest: hex(path + (texts[path] || '')), size: texts[path] != null ? utf8(texts[path]).length : binaries[path] });
    const metaInputs = img => layers(texts, img).map((l, n) => input('metadata-' + n, l));
    const decimal = v => Number.isInteger(v) ? v.toFixed(1) : String(v);
    const invalidJson = new Set();
    for (const src of Object.keys(texts).filter(k => k.endsWith('.json'))) {
      try { JSON.parse(texts[src]); } catch (e) { invalidJson.add(src); findings.push(finding('InvalidJson', 'Error', src, String(e.message).toLowerCase())); }
    }
    // Effective RepetitionTime of an image, or why there is none.
    const timing = img => {
      const broken = layers(texts, img).find(l => invalidJson.has(l));
      if (broken) return { error: finding('MetadataResolution', 'Error', img, `applicable JSON sidecar '${broken}' has no decoded metadata`) };
      const f = (metadata(texts, img).fields || {}).RepetitionTime;
      if (!f) return {};
      if (!(typeof f.value === 'number' && isFinite(f.value) && f.value > 0))
        return { error: finding('InconsistentMetadata', 'Error', img, 'RepetitionTime: must be finite and positive, got ' + (typeof f.value === 'number' ? decimal(f.value) : JSON.stringify(f.value))) };
      return { tr: f.value, meta: { RepetitionTime: { origin: f.origin, value: f.value } } };
    };
    const header = (img, dims, spacing) => ({ byteOrder: 'LittleEndian', dimensions: dims, headerOnly: true, niftiVersion: 1,
      qformCode: noTransform(img) ? 0 : 1, qformDeclared: !noTransform(img), sformCode: noTransform(img) ? 0 : 1, sformDeclared: !noTransform(img), spatialUnit: 'millimeter', temporalUnit: 'second', unitsCode: 10,
      ...(spacing != null ? { temporalSpacingSeconds: spacing } : {}) });
    for (const [s] of subjects) {
      const p = `sub-${s}/`, bold = `${p}func/sub-${s}_task-nback_run-1_bold.nii.gz`, rest = `${p}func/sub-${s}_task-rest_bold.nii.gz`, events = `${p}func/sub-${s}_task-nback_run-1_events.tsv`;
      const ap = `${p}fmap/sub-${s}_dir-AP_epi.nii.gz`, pa = `${p}fmap/sub-${s}_dir-PA_epi.nii.gz`, anat = `${p}anat/sub-${s}_T1w.nii.gz`, dwi = `${p}dwi/sub-${s}_dwi.nii.gz`;
      // IntendedFor links run from each corrected run to the fieldmap that names it.
      const intended = invalidJson.has(stem(ap) + '.json') ? [] : ((parse(texts[stem(ap) + '.json']) || {}).IntendedFor || []).map(u => u.replace(/^bids::/, ''));
      intended.filter(t => !(t in binaries)).forEach(t => findings.push(finding('FieldmapMissingTarget', 'Error', ap, 'IntendedFor: target image is absent or unresolved: ' + t)));
      intended.filter(t => t in binaries).forEach(t => links.push({ from: t, kind: 'bids.intended-for', to: [ap], label: null,
        evidence: [{ artifact: ap, document: stem(ap) + '.json', field: 'IntendedFor' }] }));
      links.push({ from: bold, kind: 'bids.task-events', to: [events], label: null,
        evidence: [{ kind: 'companion-selection', artifact: bold, document: events, rule: 'bids-mri-companions-v1:Events', datasetRoot: null, matchedEntities: { run: '1', sub: s, task: 'nback' }, applicable: [events] }] });
      links.push({ from: dwi, kind: 'bids.dwi-gradients', to: [stem(dwi) + '.bval', stem(dwi) + '.bvec'], label: null,
        evidence: ['Bval', 'Bvec'].map(k => ({ kind: 'companion-selection', artifact: dwi, document: stem(dwi) + '.' + k.toLowerCase(), rule: 'bids-mri-companions-v1:' + k, datasetRoot: null, matchedEntities: { sub: s }, applicable: [stem(dwi) + '.' + k.toLowerCase()] })) });

      // Header consistency on every image with sidecar timing; TR disagreements stay warnings.
      for (const [img, dims, hdr] of [[anat, [176, 256, 256], null], [ap, [64, 64, 40], null], [pa, [64, 64, 40], null], [bold, [64, 64, 40, VOLUMES], headerTr(s)], [rest, [64, 64, 40, VOLUMES], restTr(s)]]) {
        const t = timing(img), fs = [];
        if (t.error) fs.push(t.error);
        else if (hdr != null && t.tr != null && Math.abs(t.tr - hdr) > Math.max(1e-6, 1e-5 * hdr))
          fs.push(finding('MriRepetitionTimeMismatch', 'Warning', img, 'RepetitionTime: authored TR differs from header spacing converted to seconds'));
        if (noTransform(img)) fs.push(finding('MriTransformUnavailable', 'Warning', img, 'qform_code/sform_code: neither transform is declared; orientation is unavailable'));
        checks.push({ rule: 'bids-mri-header-consistency-v1+nifti-header-observations-v2+sanctity-prefix-v1', target: img,
          inputs: [input('image', img), ...metaInputs(img)],
          facts: { ...header(img, dims, hdr), repetitionTimeAbsoluteTolerance: 1e-6, repetitionTimeRelativeTolerance: 1e-5, ...(t.meta ? { timingMetadata: t.meta } : {}) }, findings: fs });
        findings.push(...fs);
      }
      // Event timing against the nominal acquisition window (volumeCount × RepetitionTime); a table with errors is not compared.
      const t = timing(bold), ev = readEvents(texts[events], events), rows = ev.rows;
      const end = t.tr != null && ev.valid ? +(VOLUMES * t.tr).toFixed(6) : null;
      const ef = end == null ? [] : rows.map(([o, d, line]) => o + d > end ? finding('EventsOutsideWindow', 'Warning', events,
        `event extends beyond the recorded comparison window ending at ${decimal(end)} s; review timing alignment row ${line} column 1`) : null).filter(Boolean);
      const onsets = rows.map(r => r[0]);
      checks.push({ rule: 'bids-events-content-v1+bids-events-timing-v1+nifti-header-observations-v2+sanctity-prefix-v1', target: bold,
        inputs: [input('events', events), input('image', bold), ...metaInputs(bold)],
        facts: { columns: ev.columns, eventCount: rows.length + ev.missingOnset, ...(onsets.length ? { earliestOnsetSeconds: Math.min(...onsets), latestOnsetSeconds: Math.max(...onsets) } : {}),
          missingDurationCount: ev.missingDuration, missingOnsetCount: ev.missingOnset, headerOnly: true, timeOrigin: 'first stored volume; no discarded-volume offset applied', volumeCount: VOLUMES, volumeTimingOriginSeconds: 0,
          ...(end != null ? { comparisonEndSeconds: end, endBasis: 'nominal volumeCount * RepetitionTime', lastVolumeOnsetSeconds: +((VOLUMES - 1) * t.tr).toFixed(6), timingMode: 'Regular', timingMetadata: t.meta } : {}) },
        findings: [...ev.issues, ...ef] });
      findings.push(...ev.issues, ...ef);
      const bval = stem(dwi) + '.bval', bvec = stem(dwi) + '.bvec', g = readGradients(texts[bval], texts[bvec], bval, bvec, 65);
      checks.push({ rule: 'bids-dwi-consistency-v1+nifti-header-observations-v2+sanctity-prefix-v1', target: dwi,
        inputs: [input('bval', bval), input('bvec', bvec), input('image', dwi)],
        facts: { ...header(dwi, [96, 96, 60, 65], null), bvalCount: g.bvalCount, bvecCount: g.bvecCount, gradientConvention: 'FSL image-axis components; no reorientation',
          lowBMaximum: 50, unitNormTolerance: 0.01, volumeCount: 65, zeroNormTolerance: 1e-6 }, findings: g.issues });
      findings.push(...g.issues);
    }
    for (const bp of Object.keys(texts).filter(k => /(^|\/)beh\/[^/]+_beh\.tsv$/.test(k)).sort()) {
      const b = readBehaviour(texts[bp], bp);
      checks.push({ rule: BEH_RULE, target: bp, inputs: [input('table', bp)], facts: b.facts, findings: b.issues });
      findings.push(...b.issues);
    }
    const entries = new Map();
    for (const f of files) {
      f.split('/').slice(0, -1).forEach((_, i, a) => entries.set(a.slice(0, i + 1).join('/'), { path: a.slice(0, i + 1).join('/'), kind: 'directory' }));
      entries.set(f, { path: f, kind: 'file', size: texts[f] != null ? utf8(texts[f]).length : binaries[f] });
    }
    findings.sort((a, b) => (a.path || '').localeCompare(b.path || '') || a.code.localeCompare(b.code));
    return { findings, checks, links, entries: [...entries.values()].sort((a, b) => a.path.localeCompare(b.path)),
      admissible: !findings.some(f => f.severity === 'Error') };
  }
  const identityOf = texts => {
    const manifest = hex('manifest:' + JSON.stringify(Object.entries(texts).sort()) + JSON.stringify(binaries));
    const interpretation = hex('interp:native-reference-v7:' + manifest);
    return { manifest, interpretation, assessment: hex('assess:' + policyOf(texts) + interpretation) };
  };
  const policyOf = texts => behavioural(texts) ? 'repository-mri-behavioural-admission-v1' : POLICY;
  const assessment = (seed, i, texts) => ({ id: hex('assess' + seed), policy: policyOf(texts), admissible: i.admissible, findings: i.findings, checks: i.checks });
  function releaseJson(id) {
    const r = releases.get(id), i = interpret(r.texts);
    const ident = r.identity || identityOf(r.texts);
    return { ref: { dataset: DS, release: id }, parent: r.parent, manifest: ident.manifest, interpretation: ident.interpretation,
      profile: behavioural(r.texts) ? 'native-mri-behavioural-v1' : 'native-reference-v7', coverage: 'Context-selected bids4s names and JSON inheritance; basic description/timing checks. Explicit NIfTI B0 fieldmap groups and IntendedFor links. Inherited DWI gradients and BOLD event companions. Bounded MRI dimensions, units and TR/header comparison; discrepancies remain observations. No full BIDS conformance validation.' + (behavioural(r.texts) ? ' Strict bounded behavioural tables, optional corresponding events and local stimuli; no behavioural clock qualification.' : ''), assessment: { ...assessment(id, i, r.texts), id: ident.assessment }, acceptedAt: r.acceptedAt, entries: i.entries,
      relationships: { version: 'bids-fieldmap-associations-v1+bids-mri-companions-v1+bids-phasediff-family-v1', evidenceVersion: 2, links: i.links } };
  }

  const grants = new Map(), selections = new Map(), candidates = new Map();
  const clone = v => JSON.parse(JSON.stringify(v));
  const fail = (status, code) => Promise.reject(Object.assign(new Error(code), { status, code }));
  const later = v => new Promise(res => setTimeout(() => res(v), window.SanctityDemo ? window.SanctityDemo.latency : 90));

  function request(method, url, { body, headers, raw }) {
    const [path, qs] = url.split('?');
    const q = new URLSearchParams(qs || '');
    const seg = path.split('/').filter(Boolean).map(decodeURIComponent);
    const b = typeof body === 'string' ? parse(body) : body;
    return later().then(() => {
      if (method === 'GET' && path === '/v1/me') return { principal: window.SanctityDemo.principal };
      if (seg[1] === 'datasets' && seg[2] !== DS) return fail(404, 'not_found');
      if (method === 'GET' && seg.length === 3 && seg[1] === 'datasets') return { id: DS, head };
      if (seg[1] === 'datasets' && seg[3] === 'releases') {
        const id = seg[4], rel = releases.get(id);
        if (!rel) return fail(404, 'not_found');
        if (method === 'GET' && seg.length === 5) return releaseJson(id);
        if (method === 'GET' && seg[5] === 'metadata') { const m = metadata(rel.texts, q.get('path')); return m || fail(404, 'not_found'); }
        if (method === 'GET' && seg[5] === 'file') {
          const p = q.get('path');
          if (rel.texts[p] == null && !(p in binaries)) return fail(404, 'not_found');
          const bytes = utf8(rel.texts[p] != null ? rel.texts[p] : 'synthetic image bytes for ' + p + '\n');
          const done = digest => new Response(bytes, { headers: { 'X-Sanctity-SHA256': digest, 'X-Sanctity-Size': String(bytes.length) } });
          return crypto.subtle ? crypto.subtle.digest('SHA-256', bytes).then(d => done([...new Uint8Array(d)].map(x => x.toString(16).padStart(2, '0')).join(''))) : done(hex(p));
        }
        if (method === 'POST' && seg[5] === 'corrections') {
          if (!b || typeof b.path !== 'string' || typeof b.text !== 'string' || typeof b.reason !== 'string' || !/^(?!\/)(?!.*(^|\/)\.\.?(\/|$))[^\u0000]+$/.test(b.path) || b.path in binaries) return fail(400, 'invalid_request');
          const name = b.path.split('/').pop();
          if (!/\.json$/.test(name) && !['dwi.bval', 'dwi.bvec', 'events.tsv', 'beh.tsv'].some(x => name === x || name.endsWith('_' + x))) return fail(400, 'invalid_request');
          const texts = { ...rel.texts, [b.path]: b.text.endsWith('\n') ? b.text : b.text + '\n' };
          const after = interpret(texts);
          const metadataChanges = [];
          for (const img of Object.keys(binaries)) {
            const m0 = metadata(rel.texts, img), m1 = metadata(texts, img);
            const names = [...new Set([...Object.keys(m0.fields), ...Object.keys(m1.fields)])].sort();
            const fields = names.filter(n => JSON.stringify(m0.fields[n] && [m0.fields[n].value, m0.fields[n].origin]) !== JSON.stringify(m1.fields[n] && [m1.fields[n].value, m1.fields[n].origin]))
              .map(n => ({ name: n, before: m0.fields[n] || null, after: m1.fields[n] || null }));
            if (fields.length) metadataChanges.push({ path: img, fields });
          }
          const id = uuid();
          const ident = identityOf(texts);
          const cand = { id, dataset: DS, base: seg[4], generation: 0, manifest: ident.manifest, interpretation: ident.interpretation, assessment: { ...assessment(id, after, texts), id: ident.assessment } };
          candidates.set(id, { cand, texts, base: seg[4] });
          const sz = t => utf8(t).length;
          return clone({ candidate: cand,
            contentChanges: [{ path: b.path, before: rel.texts[b.path] == null ? null : { path: b.path, kind: 'file', size: sz(rel.texts[b.path]) }, after: { path: b.path, kind: 'file', size: sz(texts[b.path]) } }],
            metadataChanges, relationshipChanges: [] });
        }
      }
      if (method === 'GET' && seg[1] === 'candidates') { const c = candidates.get(seg[2]); return c ? clone(c.cand) : fail(404, 'not_found'); }
      if (method === 'POST' && seg[1] === 'candidates' && seg[3] === 'accept') {
        const c = candidates.get(seg[2]);
        if (!c) return fail(404, 'not_found');
        const key = headers['Idempotency-Key'];
        if (c.receipt) return c.receipt.key === key ? clone(c.receipt) : fail(409, 'conflict');
        if (!c.cand.assessment.admissible) return fail(422, 'admission_rejected');
        if (c.base !== head) return fail(409, 'conflict');
        const nr = uuid(), at = new Date().toISOString();
        // Acceptance binds the candidate's exact manifest, interpretation and assessment to the new release.
        releases.set(nr, { texts: c.texts, parent: head, acceptedAt: at,
          identity: { manifest: c.cand.manifest, interpretation: c.cand.interpretation, assessment: c.cand.assessment.id } }); head = nr;
        c.receipt = { ref: { dataset: DS, release: nr }, candidate: seg[2], key, acceptedAt: at };
        return clone(c.receipt);
      }
      if (method === 'POST' && seg[1] === 'datasets' && seg[3] === 'grants') {
        const read = new Set(['ReadMetadata', 'ReadContent']);
        const ok = b && Array.isArray(b.actions) && b.actions.length && !b.actions.includes('ManageAccess') && (!b.release || b.actions.every(a => read.has(a)));
        if (!ok) return fail(400, 'invalid_request');
        const g = { id: uuid(), dataset: DS, recipient: b.recipient, release: b.release || null, actions: [...b.actions].sort() };
        grants.set(g.id, g); return clone(g);
      }
      if (method === 'DELETE' && seg[1] === 'datasets' && seg[3] === 'grants') return grants.delete(seg[4]) ? null : fail(404, 'not_found');
      if (method === 'POST' && path === '/v1/selections') {
        const find = (p, f) => !p ? null : f(p) ?? (p.conditions || []).map(x => find(x, f)).find(x => x != null) ?? null;
        const minAge = find(b.participants, p => p.op === 'fieldAtLeast' && p.name === 'age' ? Number(p.value) : null);
        const suffix = find(b.artifacts, a => a.op === 'suffix' ? a.value : null);
        const task = find(b.artifacts, a => a.op === 'entity' && a.name === 'task' ? a.value : null);
        const hits = [];
        for (const ref of b.releases) for (const [s, age] of subjects) {
          if (minAge != null && age < minAge) continue;
          for (const p of Object.keys(binaries)) {
            if (!p.startsWith('sub-' + s + '/')) continue;
            if (suffix && !p.endsWith('_' + suffix + '.nii.gz')) continue;
            if (task && !p.includes('task-' + task + '_')) continue;
            hits.push({ ref, path: p, participant: s, session: null });
          }
        }
        hits.sort((x, y) => x.path.localeCompare(y.path));
        const id = hex(body);
        selections.set(id, hits);
        return { id, total: hits.length, releases: b.releases };
      }
      if (method === 'GET' && seg[1] === 'selections') {
        const hits = selections.get(seg[2]);
        if (!hits) return fail(404, 'not_found');
        const offset = +(q.get('offset') || 0), limit = +(q.get('limit') || 100);
        return { id: seg[2], total: hits.length, offset, hits: hits.slice(offset, offset + limit) };
      }
      return fail(404, 'not_found');
    });
  }
  window.SanctityDemo = { request, dataset: DS, principal: ME, releases: R, latency: 90 };
})();
