package com.greedy.section01.list.run;

public class BookDTO implements Comparable {

	/* 도서 정보를 저장 할 DTO 클래스를 만들어 보자. */
	private int number;
	private String title;
	private String author;
	private int price;
	
	public BookDTO() {
	}
	
	public BookDTO(int number, String title, String author, int price) {
		this.number = number;
		this.title = title;
		this.author = author;
		this.price = price;
	}

	int getNumber() {
		return number;
	}

	void setNumber(int number) {
		this.number = number;
	}

	String getTitle() {
		return title;
	}

	void setTitle(String title) {
		this.title = title;
	}

	public String getAuthor() {
		return author;
	}

	void setAuthor(String author) {
		this.author = author;
	}

	public int getPrice() {
		return price;
	}

	void setPrice(int price) {
		this.price = price;
	}

	@Override
	public String toString() {
		return "BookDTO [number=" + number + ", title=" + title + ", author=" + author + ", price=" + price + "]";
	}
	
	/* 정렬 기준을 작성하기 위한 compareTo 메소드 오버라이딩 */
	@Override
	public int compareTo(Object o) {
		
		/* 가격에 대한 정렬 */
		/* 오름차순 */
//		return this.price - ((BookDTO)o).price;
		
		/* 내림차순 */
//		return -(this.price - ((BookDTO)o).price);
//		return ((BookDTO)o).price - this.price;
		
		/* 도서 번호에 대한 정렬 */
		/* 오름차순 */
//		return this.number - ((BookDTO)o).number;
		
		/* 내림 차순 */
//		return -(this.number - ((BookDTO)o).number);
//		return ((BookDTO)o).number - this.number;
		
		/* 책 제목에 대한 정렬 */
		/* 오름차순 */
//		return this.title.compareTo(((BookDTO)o).title);
		
		/*내림 차순 */
//		return - (this.title.compareTo(((BookDTO)o).title));
		return ((BookDTO)o).title.compareTo(this.title);
		
	}
}
