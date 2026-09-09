class StorageError(Exception):
    def __init__(self, code: str, message: str, *, page_id=None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.page_id = page_id

    def to_dict(self):
        return {
            "stage": "PAGE",
            "code": self.code,
            "message": self.message,
            "pageId": self.page_id,
        }
