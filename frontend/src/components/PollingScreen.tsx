import { useEffect, useRef } from 'react';
import { getJobStatus } from '../api';
import type { JobStatusResponse } from '../api';

interface Props {
  jobId: number;
  onSettled: (job: JobStatusResponse) => void;
}

const POLL_INTERVAL_MS = 2500;

export default function PollingScreen({ jobId, onSettled }: Props) {
  const onSettledRef = useRef(onSettled);
  onSettledRef.current = onSettled;

  useEffect(() => {
    let stopped = false;
    let intervalId: number;

    const poll = async () => {
      try {
        const job = await getJobStatus(jobId);
        if (stopped) return;
        if (job.status === 'COMPLETE' || job.status === 'FAILED') {
          stopped = true;
          clearInterval(intervalId);
          onSettledRef.current(job);
        }
      } catch {
        // transient network/fetch error — leave the interval running and try again
      }
    };

    poll();
    intervalId = window.setInterval(poll, POLL_INTERVAL_MS);

    return () => {
      stopped = true;
      clearInterval(intervalId);
    };
  }, [jobId]);

  return (
    <div className="card polling-card">
      <div className="spinner" aria-hidden="true" />
      <h1>Processing your hike…</h1>
      <p className="muted">
        This usually takes under a minute. We'll update automatically — no need to refresh.
      </p>
    </div>
  );
}
