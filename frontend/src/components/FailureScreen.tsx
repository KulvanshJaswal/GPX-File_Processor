import type { JobStatusResponse } from '../api';

interface Props {
  job: JobStatusResponse;
  onReset: () => void;
}

export default function FailureScreen({ job, onReset }: Props) {
  return (
    <div className="card failure-card">
      <span className="status-badge status-badge--failure">Failed</span>
      <h1>Something went wrong</h1>
      <p className="error-text">{job.errorMessage || 'The job failed for an unknown reason.'}</p>
      <button type="button" className="btn btn-primary" onClick={onReset}>
        Try another file
      </button>
    </div>
  );
}
