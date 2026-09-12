import type { JobStatusResponse } from '../api';
import { pdfUrl } from '../api';

interface Props {
  job: JobStatusResponse;
  onReset: () => void;
}

export default function SuccessScreen({ job, onReset }: Props) {
  return (
    <div className="card success-card">
      <span className="status-badge status-badge--success">Complete</span>
      <h1>Your trail report is ready</h1>

      <dl className="stat-list">
        <div className="stat">
          <dt>Distance</dt>
          <dd>{job.distanceKm.toFixed(2)} km</dd>
        </div>
        <div className="stat">
          <dt>Difficulty</dt>
          <dd>{job.difficulty}</dd>
        </div>
      </dl>

      <a className="btn btn-primary" href={pdfUrl(job.jobId)}>
        Download PDF report
      </a>
      <button type="button" className="btn btn-ghost" onClick={onReset}>
        Upload another file
      </button>
    </div>
  );
}
