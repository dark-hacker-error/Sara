"""Runtime config store.

This is what makes "reload without restart" real. The store holds one `AppConfig` snapshot;
the agent tree is rebuilt from the snapshot on every delegation, so a config push takes
effect on the very next task with no restart, no cache invalidation and no races.
"""

from __future__ import annotations

import asyncio
import logging

from .config import AppConfig, AgentSpec, default_config

logger = logging.getLogger(__name__)


class ConfigStore:
    def __init__(self, initial: AppConfig | None = None) -> None:
        self._config = initial or default_config()
        self._lock = asyncio.Lock()
        self._generation = 0

    @property
    def generation(self) -> int:
        """Bumped on every update; useful for logs and for the app to confirm a push landed."""
        return self._generation

    def snapshot(self) -> AppConfig:
        return self._config

    def replace(self, config: AppConfig) -> None:
        """Swap the whole snapshot. Used at startup and by tests."""
        self._config = config
        self._generation += 1

    async def update(
        self,
        agents: list[AgentSpec] | None = None,
        live_model: str | None = None,
    ) -> AppConfig:
        async with self._lock:
            current = self._config
            next_config = AppConfig(
                orchestrator=current.orchestrator,
                agents=agents if agents is not None else current.agents,
                live_model=live_model or current.live_model,
            )
            self._config = next_config
            self._generation += 1
            runnable = [a.name for a in next_config.agents if a.is_runnable()]
            logger.info(
                "Config updated (gen %d): %d subagents, runnable=%s",
                self._generation,
                len(next_config.agents),
                runnable or "none",
            )
            return next_config

    def runnable_subagents(self) -> list[str]:
        return [agent.name for agent in self._config.agents if agent.is_runnable()]
