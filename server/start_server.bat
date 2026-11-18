@echo off
cd /d %~dp0

if not exist .venv (
    py -3 -m venv .venv
)

call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
pip install -r requirements.txt

if "%AICLIPPER_DOWNLOAD_TOKEN%"=="" (
    echo.
    echo AICLIPPER_DOWNLOAD_TOKEN tanimli degil.
    echo Ornek: set AICLIPPER_DOWNLOAD_TOKEN=KENDI_GUVENLI_TOKENIN
    echo.
    pause
    exit /b 1
)

python main.py
