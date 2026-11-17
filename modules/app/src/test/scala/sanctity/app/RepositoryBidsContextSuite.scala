package sanctity.app

import bids4s.BidsPath
import munit.FunSuite
import sanctity.bids.RepositoryBidsContext
import sanctity.kernel.CoverageLevel

/** The datatype rules are data. These names pin what each modality accepts and refuses. */
final class RepositoryBidsContextSuite extends FunSuite:
  private val context = RepositoryBidsContext.Repository
  private def datatype(path: String): Option[String] = context.parsePath(BidsPath(path)).toOption.flatMap(_.datatype)
  private def modality(path: String): Option[String] = datatype(path).flatMap(RepositoryBidsContext.modalityOfSpec).map(_.id)

  test("every modality accepts its documented names in its own folder") {
    val accepted = Vector(
      "sub-01/eeg/sub-01_task-rest_run-01_eeg.edf" -> "eeg",
      "sub-01/eeg/sub-01_task-rest_eeg.vhdr" -> "eeg",
      "sub-01/ses-1/eeg/sub-01_ses-1_task-rest_channels.tsv" -> "eeg",
      "sub-01/eeg/sub-01_task-rest_events.json" -> "eeg",
      "sub-01/eeg/sub-01_acq-cap_space-CapTrak_electrodes.tsv" -> "eeg",
      "sub-01/eeg/sub-01_coordsystem.json" -> "eeg",
      "sub-01/eeg/sub-01_task-rest_run-01_electrodes.tsv" -> "eeg",
      "sub-01/eeg/sub-01_task-rest_space-CapTrak_coordsystem.json" -> "eeg",
      "sub-01/eeg/sub-01_acq-cap_photo.jpg" -> "eeg",
      "sub-01/eeg/sub-01_task-rest_recording-ecg_physio.tsv.gz" -> "eeg",
      "sub-01/eeg/sub-01_task-dots_run-01_recording-eye1_physioevents.tsv.gz" -> "eeg",
      "sub-01/meg/sub-01_task-rest_meg.raw.mhd" -> "meg",
      "sub-01/meg/sub-01_task-rest_proc-sss_channels.tsv" -> "meg",
      "sub-01/meg/sub-01_task-rest_events.tsv" -> "meg",
      "sub-01/meg/sub-01_task-rest_space-CapTrak_electrodes.tsv" -> "meg",
      "sub-01/meg/sub-01_task-rest_coordsystem.json" -> "meg",
      "sub-01/nirs/sub-01_task-tap_recording-ecg_physio.tsv.gz" -> "nirs",
      "sub-01/pet/sub-01_task-rest_trc-FDG_events.tsv" -> "pet",
      "sub-01/pet/sub-01_task-rest_trc-FDG_recording-ecg_physio.tsv.gz" -> "pet",
      "sub-01/perf/sub-01_dir-AP_part-mag_echo-1_asl.nii.gz" -> "perf",
      "sub-01/perf/sub-01_acq-pcasl_asllabeling.png" -> "perf",
      "sub-01/perf/sub-01_noRF.nii.gz" -> "perf",
      "sub-01/motion/sub-01_task-walk_tracksys-imu_channels.tsv" -> "motion",
      "sub-01/ieeg/sub-01_task-rest_ieeg.nwb" -> "ieeg",
      "sub-01/ieeg/sub-01_space-MNI152Lin_electrodes.tsv" -> "ieeg",
      "sub-01/meg/sub-01_task-rest_proc-sss_split-01_meg.fif" -> "meg",
      "sub-01/meg/sub-01_acq-crosstalk_meg.fif" -> "meg",
      "sub-01/meg/sub-01_acq-calibration_meg.dat" -> "meg",
      "sub-01/meg/sub-01_headshape.pos" -> "meg",
      "sub-01/meg/sub-01_task-rest_markers.sqd" -> "meg",
      "sub-01/nirs/sub-01_task-tap_nirs.snirf" -> "nirs",
      "sub-01/nirs/sub-01_optodes.tsv" -> "nirs",
      "sub-01/pet/sub-01_trc-FDG_rec-acdyn_pet.nii.gz" -> "pet",
      "sub-01/pet/sub-01_trc-FDG_recording-manual_blood.tsv" -> "pet",
      "sub-01/perf/sub-01_asl.nii.gz" -> "perf",
      "sub-01/perf/sub-01_aslcontext.tsv" -> "perf",
      "sub-01/motion/sub-01_task-walk_tracksys-imu_motion.tsv" -> "motion",
      "sub-01/motion/sub-01_task-walk_events.tsv" -> "motion",
      "sub-01/beh/sub-01_task-choice_beh.tsv" -> "beh",
      "sub-01/func/sub-01_task-rest_bold.nii.gz" -> "mri",
      "sub-01/func/sub-01_task-rest_events.tsv" -> "mri",
      "sub-01/anat/sub-01_T1w.nii.gz" -> "mri")
    accepted.foreach((path, expected) => assertEquals(modality(path), Some(expected), path))
  }

  test("names that break a modality's rules are refused") {
    val refused = Vector(
      "sub-01/eeg/sub-01_eeg.edf", // a recording needs a task
      "sub-01/eeg/sub-01_task-rest_photo.jpg", // a photograph does not belong to a task
      "sub-01/eeg/sub-01_task-rest_eeg.fif", // not an EEG format
      "sub-01/eeg/sub-01_task-rest_eeg.nwb", // NWB is an intracranial format
      "sub-01/func/sub-01_task-rest_eeg.edf", // wrong folder
      "sub-01/meg/sub-01_acq-other_meg.dat", // only a calibration file uses this form
      "sub-01/meg/sub-01_acq-calibration_meg.fif", // a calibration file is .dat; crosstalk is .fif
      "sub-01/meg/sub-01_task-rest_split-01_events.tsv", // events are not split
      "sub-01/nirs/sub-01_task-tap_optodes.tsv", // optode positions do not belong to a task
      "sub-01/pet/sub-01_acq-x_trc-FDG_pet.nii.gz", // PET images take no acquisition label
      "sub-01/perf/sub-01_dir-AP_asllabeling.jpg", // a labelling screenshot has no phase-encoding direction
      "sub-01/pet/sub-01_trc-FDG_blood.tsv", // blood data must name its recording
      "sub-01/motion/sub-01_task-walk_motion.tsv", // motion data must name its tracking system
      "sub-01/nirs/sub-01_task-tap_run-one_nirs.snirf") // a run is an index
    refused.foreach(path => assertEquals(datatype(path), None, path))
  }

  test("folders map to modalities and levels") {
    def level(folder: String) = RepositoryBidsContext.modalityOfFolder(folder).map(_.level)
    Vector("anat", "func", "dwi", "fmap", "beh").foreach(f => assertEquals(level(f), Some(CoverageLevel.Assessed), f))
    Vector("eeg", "ieeg", "meg", "nirs", "pet", "perf", "motion").foreach(f => assertEquals(level(f), Some(CoverageLevel.Described), f))
    Vector("micr", "mrs", "emg", "phenotype").foreach(f => assertEquals(level(f), None, f))
  }

  test("a role shared by several data types claims only its weakest level") {
    assertEquals(RepositoryBidsContext.levelOfRole("bold", "json"), Some(CoverageLevel.Assessed))
    assertEquals(RepositoryBidsContext.levelOfRole("eeg", "json"), Some(CoverageLevel.Described))
    assertEquals(RepositoryBidsContext.levelOfRole("events", "json"), Some(CoverageLevel.Described))
    assertEquals(RepositoryBidsContext.levelOfRole("physio", "tsv.gz"), Some(CoverageLevel.Described))
    assertEquals(RepositoryBidsContext.levelOfRole("notes", "txt"), None)
  }
