from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes import api_router, health_router
from app.config import get_settings
from app.errors import register_error_handlers
from app.middleware import RequestBoundaryMiddleware


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    get_settings()
    yield


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(
        title=settings.app_name,
        version=settings.version,
        debug=settings.debug,
        description=(
            "企业资料结构化与供需匹配规则服务。"
            "政策问答未在本服务启用，生产知识库主链路位于 Java ai-adapter。"
        ),
        lifespan=lifespan,
    )

    register_error_handlers(app)
    app.include_router(health_router)
    app.include_router(api_router, prefix=settings.api_prefix)
    app.add_middleware(
        RequestBoundaryMiddleware,
        max_request_bytes=settings.max_request_bytes,
    )
    return app


app = create_app()


def run() -> None:
    import uvicorn

    settings = get_settings()
    uvicorn.run("app.main:app", host=settings.host, port=settings.port)


if __name__ == "__main__":
    run()
