// Stand-in for Eden's web_service/web_result.h: the room never talks to a web service.
#pragma once
#include <string>
#include "common/common_types.h"
namespace WebService {
struct WebResult {
    enum class Code : u32 { Success, InvalidURL, CredentialsMissing, LibError, HttpError, WrongContent, NoWebservice };
    Code result_code;
    std::string result_string;
    std::string returned_data;
};
} // namespace WebService
