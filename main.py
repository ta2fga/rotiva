"""OBD2 Tanı Pro - giriş noktası."""
import sys

from PySide6.QtWidgets import QApplication

from app import APP_NAME, APP_ORG
from app.ui import theme
from app.ui.main_window import MainWindow
from app.ui.icon import app_icon


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setOrganizationName(APP_ORG)
    app.setStyleSheet(theme.QSS)
    app.setWindowIcon(app_icon())
    win = MainWindow()
    win.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
