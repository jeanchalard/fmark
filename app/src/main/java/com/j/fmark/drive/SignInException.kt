package com.j.fmark.drive

import java.lang.Exception

class SignInException(s : String) : Exception(s)
class FolderNotUniqueException(s : String) : Exception(s)
class NotAFolderException(s : String) : Exception(s)
