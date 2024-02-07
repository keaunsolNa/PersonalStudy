package com.greedy.section02.uses;

public class MemberSelectmanager {

	public MemberDTO[] selectAllMembers() {
		
		/* 파일이나 DB에 저장된 회원의 정보를 불러와서 객체 배열로 받았다고 가정 */
		MemberDTO[] md = new MemberDTO[] {
				new MemberDTO(1, "user01", "pass01", "홍길동", 20, '남'),
				new MemberDTO(2, "user02", "pass02", "유관순", 16, '여'),
				new MemberDTO(3, "user03", "pass03", "이순신", 40, '남'),
				new MemberDTO(4, "user04", "pass04", "신사임당", 36, '여'),
				new MemberDTO(5, "user05", "pass05", "윤봉길", 22, '남'),
		};
		
		return md;
	}

}
