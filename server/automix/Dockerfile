FROM pytorch/pytorch:2.7.1-cuda12.6-cudnn9-runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir 'demucs-infer==4.4.0' \
    'all-in-one-infer @ git+https://github.com/openmirlab/all-in-one-infer.git@566f96c' \
    && python -c 'from allin1_infer import AllInOneSession'

WORKDIR /app
COPY automix/worker.py automix/audio_muse.py automix/audio_levels.py navidrome-journal/journal.py ./
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 TORCH_HOME=/state/models
CMD ["python", "worker.py"]
